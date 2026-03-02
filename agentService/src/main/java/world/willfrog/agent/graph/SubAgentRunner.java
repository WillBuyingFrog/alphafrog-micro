package world.willfrog.agent.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.service.AgentLlmRequestSnapshotBuilder;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.tool.ToolRouter;
import world.willfrog.agent.workflow.StructuredPlanningSupport;
import world.willfrog.agent.workflow.TodoExecutionRecord;
import world.willfrog.agent.workflow.TodoParamResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
/**
 * 子代理执行器。
 * <p>
 * 子代理不直接输出最终回复，而是：
 * 1. 先生成线性步骤计划；
 * 2. 按步骤调用工具；
 * 3. 产出可供主流程合并的局部结论。
 */
public class SubAgentRunner {
    /** 子代理规划最大重试次数（用于修正无效工具/格式问题）。 */
    private static final int MAX_PLAN_ATTEMPTS = 3;

    /** 工具路由器。 */
    private final ToolRouter toolRouter;
    /** Python 代码执行重试节点。 */
    private final PythonCodeRefinementNode pythonCodeRefinementNode;
    /** JSON 序列化/反序列化工具。 */
    private final ObjectMapper objectMapper;
    /** 事件服务（记录子代理过程）。 */
    private final AgentEventService eventService;
    /** 可观测指标聚合服务。 */
    private final AgentObservabilityService observabilityService;
    /** LLM 原始请求快照构建器。 */
    private final AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    /** Prompt 配置服务。 */
    private final AgentPromptService promptService;
    /** 占位符解析器（主流程与子流程共用）。 */
    private final TodoParamResolver paramResolver;
    /** 本地运行时配置。 */
    private final AgentLlmLocalConfigLoader localConfigLoader;
    /** 基础运行时配置。 */
    private final AgentLlmProperties llmProperties;

    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("\\$\\{[^}]+}");

    /**
     * 子代理请求参数。
     */
    @Data
    @Builder
    public static class SubAgentRequest {
        /** run ID。 */
        private String runId;
        /** 用户 ID。 */
        private String userId;
        /** 对应的并行任务 ID。 */
        private String taskId;
        /** 子任务目标。 */
        private String goal;
        /** 上下文补充。 */
        private String context;
        /** 上游透传的初始参数（已完成占位符解析）。 */
        private Map<String, Object> seedArgs;
        /** 可用工具白名单。 */
        private Set<String> toolWhitelist;
        /** 工具规格列表（用于 LLM 请求记录）。 */
        private List<ToolSpecification> toolSpecifications;
        /** 允许的最大步骤数。 */
        private int maxSteps;
        /** 端点名。 */
        private String endpointName;
        /** 端点 baseUrl。 */
        private String endpointBaseUrl;
        /** 模型名。 */
        private String modelName;
    }

    /**
     * 子代理执行结果。
     */
    @Data
    @Builder
    public static class SubAgentResult {
        /** 是否成功。 */
        private boolean success;
        /** 子代理结论文本。 */
        private String answer;
        /** 错误信息（失败时）。 */
        private String error;
        /** 逐步执行记录。 */
        private List<Map<String, Object>> steps;
    }

    /**
     * 执行子代理任务。
     *
     * @param request 子代理请求
     * @param model   聊天模型
     * @return 子代理执行结果
     */
    public SubAgentResult run(SubAgentRequest request, ChatLanguageModel model) {
        if (request == null || request.getGoal() == null || request.getGoal().isBlank()) {
            return SubAgentResult.builder().success(false).error("sub_agent goal missing").build();
        }
        String previousPhase = AgentContext.getPhase();
        String previousStage = AgentContext.getStage();
        AgentContext.StructuredOutputSpec previousStructuredOutputSpec = AgentContext.getStructuredOutputSpec();
        AgentContext.setPhase(AgentObservabilityService.PHASE_SUB_AGENT);
        Set<String> whitelist = request.getToolWhitelist() == null ? Collections.emptySet() : request.getToolWhitelist();
        String tools = whitelist.stream().sorted().collect(Collectors.joining(", "));
        String systemPrompt = buildPlannerPrompt(tools, request.getMaxSteps());

        List<Map<String, Object>> executedSteps = new ArrayList<>();
        try {
            // 第一步：生成“可执行的线性步骤 JSON”。若出现无效工具，自动要求模型重规划。
            JsonNode stepsNode = null;
            String lastPlanError = "sub_agent plan generation failed";
            String lastPlanErrorCategory = StructuredPlanningSupport.CATEGORY_SCHEMA_VALIDATION_ERROR;
            String retryHint = "";
            String planTraceId = "";
            int maxPlanAttempts = resolveSubAgentPlanningMaxAttempts();
            boolean structuredEnabled = subAgentStructuredEnabled();
            observabilityService.markPlanningStructured(request.getRunId(), structuredEnabled);
            observabilityService.setLastPlanningErrorCategory(request.getRunId(), "");
            for (int attempt = 1; attempt <= maxPlanAttempts; attempt++) {
                observabilityService.incrementPlanningAttempts(request.getRunId(), true);
                List<dev.langchain4j.data.message.ChatMessage> planMessages = List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage("目标: " + request.getGoal()
                                + "\n上下文: " + (request.getContext() == null ? "" : request.getContext())
                                + "\n" + retryHint)
                );
                AgentContext.setStage("sub_agent_plan");
                AgentContext.StructuredOutputSpec loopPreviousStructuredOutputSpec = AgentContext.getStructuredOutputSpec();
                if (structuredEnabled) {
                    AgentContext.setStructuredOutputSpec(new AgentContext.StructuredOutputSpec(
                            "sub_agent_plan",
                            subAgentStructuredStrict(),
                            StructuredPlanningSupport.subAgentPlanningJsonSchema(),
                            subAgentRequireProviderParameters(),
                            subAgentAllowProviderFallbacks()
                    ));
                } else {
                    AgentContext.clearStructuredOutputSpec();
                }
                long llmStartedAt = System.currentTimeMillis();
                try {
                    Response<dev.langchain4j.data.message.AiMessage> planResp = model.generate(planMessages);
                    long llmCompletedAt = System.currentTimeMillis();
                    long llmDurationMs = llmCompletedAt - llmStartedAt;
                    String planText = planResp.content() == null ? "" : nvl(planResp.content().text());
                    Map<String, Object> planRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                            request.getEndpointName(),
                            request.getEndpointBaseUrl(),
                            request.getModelName(),
                            planMessages,
                            request.getToolSpecifications(),
                            Map.of(
                                    "stage", "sub_agent_plan",
                                    "attempt", attempt,
                                    "structured_output", structuredEnabled
                            )
                    );
                    planTraceId = observabilityService.recordLlmCall(
                            request.getRunId(),
                            AgentObservabilityService.PHASE_SUB_AGENT,
                            planResp.tokenUsage(),
                            llmDurationMs,
                            llmStartedAt,
                            llmCompletedAt,
                            request.getEndpointName(),
                            request.getModelName(),
                            null,
                            planRequestSnapshot,
                            planText
                    );
                    JsonNode root = StructuredPlanningSupport.parseStructuredJson(objectMapper, planText);
                    StructuredPlanningSupport.ValidationResult validation =
                            StructuredPlanningSupport.validateSubAgentPlan(root, request.getMaxSteps(), whitelist);
                    if (!validation.valid()) {
                        throw new StructuredPlanningSupport.StructuredPlanningException(validation.category(), validation.message());
                    }
                    stepsNode = root.path("steps");
                    observabilityService.setLastPlanningErrorCategory(request.getRunId(), "");
                    break;
                } catch (StructuredPlanningSupport.StructuredPlanningException e) {
                    lastPlanError = nvl(e.getMessage());
                    lastPlanErrorCategory = nvl(e.category());
                    observabilityService.setLastPlanningErrorCategory(request.getRunId(), lastPlanErrorCategory);
                    retryHint = "上一次规划不符合 schema，错误类别=" + nvl(lastPlanErrorCategory)
                            + "，错误=" + nvl(lastPlanError)
                            + "。请严格输出符合 JSON Schema 的结构化结果。";
                    emitEvent(request, "SUB_AGENT_PLAN_RETRY", Map.of(
                            "task_id", nvl(request.getTaskId()),
                            "attempt", attempt,
                            "max_attempts", maxPlanAttempts,
                            "error_category", nvl(lastPlanErrorCategory),
                            "reason", nvl(lastPlanError)
                    ));
                } finally {
                    if (loopPreviousStructuredOutputSpec == null) {
                        AgentContext.clearStructuredOutputSpec();
                    } else {
                        AgentContext.setStructuredOutputSpec(loopPreviousStructuredOutputSpec);
                    }
                }
            }
            if (stepsNode == null) {
                observabilityService.setLastPlanningErrorCategory(request.getRunId(), nvl(lastPlanErrorCategory));
                emitEvent(request, "SUB_AGENT_FAILED", Map.of(
                        "task_id", nvl(request.getTaskId()),
                        "error", lastPlanError,
                        "error_category", nvl(lastPlanErrorCategory)
                ));
                return SubAgentResult.builder().success(false).error(lastPlanError).build();
            }

            emitEvent(request, "SUB_AGENT_PLAN_CREATED", Map.of(
                    "task_id", nvl(request.getTaskId()),
                    "steps_count", stepsNode.size(),
                    "steps", buildStepSummary(stepsNode),
                    "endpoint", nvl(request.getEndpointName()),
                    "model", nvl(request.getModelName())
            ));
            observabilityService.addNodeCount(request.getRunId(), stepsNode.size());

            // 第二步：按计划逐步执行工具。
            for (JsonNode stepNode : stepsNode) {
                String tool = stepNode.path("tool").asText();
                if (!whitelist.contains(tool)) {
                    emitEvent(request, "SUB_AGENT_FAILED", Map.of(
                            "task_id", nvl(request.getTaskId()),
                            "error", "sub_agent tool not allowed: " + tool
                    ));
                    return SubAgentResult.builder().success(false).error("sub_agent tool not allowed: " + tool).build();
                }
                JsonNode argsNode = stepNode.path("args");
                Map<String, Object> rawArgs = argsNode.isObject() ? objectMapper.convertValue(argsNode, Map.class) : Map.of();
                Map<String, Object> resolvedRawArgs = resolveStepPlaceholders(rawArgs, executedSteps);
                Map<String, Object> args = normalizeStepArgs(tool, resolvedRawArgs, executedSteps, request.getSeedArgs());
                List<String> unresolved = collectUnresolvedPlaceholders(args);
                if (!unresolved.isEmpty()) {
                    String err = "PARAM_PLACEHOLDER_UNRESOLVED: tool=" + tool + ", placeholders=" + unresolved;
                    emitEvent(request, "SUB_AGENT_FAILED", Map.of(
                            "task_id", nvl(request.getTaskId()),
                            "error", err,
                            "error_category", "PARAM_PLACEHOLDER_UNRESOLVED",
                            "tool", tool,
                            "raw_placeholders", unresolved
                    ));
                    return SubAgentResult.builder()
                            .success(false)
                            .error(err)
                            .steps(executedSteps)
                            .build();
                }

                int stepIndex = executedSteps.size();
                emitEvent(request, "SUB_AGENT_STEP_STARTED", Map.of(
                        "task_id", nvl(request.getTaskId()),
                        "step_index", stepIndex,
                        "tool", tool,
                        "args", args
                ));
                String output;
                boolean stepSuccess;
                Map<String, Object> cachePayload = toolRouter.toEventCachePayload(null);
                AgentContext.setStage("sub_agent_step_execution");
                AgentContext.setSubAgentStepIndex(stepIndex);
                AgentContext.setDecisionContext(planTraceId, "sub_agent_plan", preview(systemPrompt));
                if ("executePython".equals(tool)) {
                    PythonCodeRefinementNode.Result refineResult = pythonCodeRefinementNode.execute(
                            PythonCodeRefinementNode.Request.builder()
                                    .goal(request.getGoal())
                                    .context(request.getContext())
                                    .codingContext(buildCodingContext(args, executedSteps, request.getContext()))
                                    .initialCode(firstNonBlank(args.get("code"), args.get("arg0")))
                                    .initialRunArgs(extractInitialPythonRunArgs(args))
                                    .datasetId(firstNonBlank(args.get("dataset_id"), args.get("datasetId"), args.get("arg1")))
                                    .datasetIds(firstNonBlank(args.get("dataset_ids"), args.get("datasetIds"), args.get("arg2"), args.get("dataset_id"), args.get("datasetId"), args.get("arg1")))
                                    .libraries(firstNonBlank(args.get("libraries"), args.get("arg3")))
                                    .timeoutSeconds(toNullableInt(args.get("timeout_seconds"), args.get("timeoutSeconds"), args.get("arg4")))
                                    .endpointName(request.getEndpointName())
                                    .endpointBaseUrl(request.getEndpointBaseUrl())
                                    .modelName(request.getModelName())
                                    .decisionLlmTraceId(planTraceId)
                                    .decisionStage("sub_agent_plan")
                                    .decisionExcerpt(preview(systemPrompt))
                                    .build(),
                            model
                    );
                    output = refineResult.getOutput();
                    stepSuccess = refineResult.isSuccess();
                    emitEvent(request, "SUB_AGENT_PYTHON_REFINED", Map.of(
                            "task_id", nvl(request.getTaskId()),
                            "step_index", stepIndex,
                            "success", stepSuccess,
                            "attempts_used", refineResult.getAttemptsUsed(),
                            "traces", summarizePythonTraces(refineResult.getTraces(), !stepSuccess)
                    ));
                } else {
                    ToolRouter.ToolInvocationResult invokeResult = toolRouter.invokeWithMeta(tool, args);
                    output = invokeResult.getOutput();
                    stepSuccess = invokeResult.isSuccess() && isStepSuccessful(tool, output);
                    cachePayload = toolRouter.toEventCachePayload(invokeResult);
                }
                AgentContext.clearSubAgentStepIndex();

                Map<String, Object> stepResult = new HashMap<>();
                stepResult.put("tool", tool);
                stepResult.put("args", args);
                stepResult.put("output", output);
                stepResult.put("success", stepSuccess);
                executedSteps.add(stepResult);

                emitEvent(request, "SUB_AGENT_STEP_FINISHED", Map.of(
                        "task_id", nvl(request.getTaskId()),
                        "step_index", stepIndex,
                        "tool", tool,
                        "success", stepSuccess,
                        "output_preview", preview(output),
                        "cache", cachePayload
                ));
                if (!stepSuccess) {
                    String err = "sub_agent step failed: tool=" + tool + ", reason=" + preview(output);
                    emitEvent(request, "SUB_AGENT_FAILED", Map.of(
                            "task_id", nvl(request.getTaskId()),
                            "error", err
                    ));
                    return SubAgentResult.builder()
                            .success(false)
                            .error(err)
                            .steps(executedSteps)
                            .build();
                }
            }

            // 第三步：把步骤执行结果再总结成可供主流程合并的结论文本。
            String summaryPrompt = promptService.subAgentSummarySystemPrompt();
            String executedStepsJson = objectMapper.writeValueAsString(executedSteps);
            List<dev.langchain4j.data.message.ChatMessage> summaryMessages = List.of(
                    new SystemMessage(summaryPrompt),
                    new UserMessage("目标: " + request.getGoal() + "\n结果: " + executedStepsJson)
            );
            AgentContext.setStage("sub_agent_summary");
            long llmStartedAt = System.currentTimeMillis();
            Response<dev.langchain4j.data.message.AiMessage> finalResp = model.generate(summaryMessages);
            long llmCompletedAt = System.currentTimeMillis();
            long llmDurationMs = llmCompletedAt - llmStartedAt;
            String finalText = finalResp.content().text();
            Map<String, Object> summaryRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                    request.getEndpointName(),
                    request.getEndpointBaseUrl(),
                    request.getModelName(),
                    summaryMessages,
                    request.getToolSpecifications(),
                    Map.of("stage", "sub_agent_summary")
            );
            observabilityService.recordLlmCall(
                    request.getRunId(),
                    AgentObservabilityService.PHASE_SUB_AGENT,
                    finalResp.tokenUsage(),
                    llmDurationMs,
                    llmStartedAt,
                    llmCompletedAt,
                    request.getEndpointName(),
                    request.getModelName(),
                    null,
                    summaryRequestSnapshot,
                    finalText
            );

            emitEvent(request, "SUB_AGENT_COMPLETED", Map.of(
                    "task_id", nvl(request.getTaskId()),
                    "steps", executedSteps.size(),
                    "endpoint", nvl(request.getEndpointName()),
                    "model", nvl(request.getModelName())
            ));

            return SubAgentResult.builder()
                    .success(true)
                    .answer(finalText)
                    .steps(executedSteps)
                    .build();
        } catch (Exception e) {
            log.warn("Sub-agent failed", e);
            emitEvent(request, "SUB_AGENT_FAILED", Map.of(
                    "task_id", nvl(request.getTaskId()),
                    "error", nvl(e.getMessage())
            ));
            return SubAgentResult.builder().success(false).error(nvl(e.getMessage())).steps(executedSteps).build();
        } finally {
            if (previousPhase == null || previousPhase.isBlank()) {
                AgentContext.clearPhase();
            } else {
                AgentContext.setPhase(previousPhase);
            }
            if (previousStage == null || previousStage.isBlank()) {
                AgentContext.clearStage();
            } else {
                AgentContext.setStage(previousStage);
            }
            AgentContext.clearSubAgentStepIndex();
            AgentContext.clearDecisionContext();
            if (previousStructuredOutputSpec == null) {
                AgentContext.clearStructuredOutputSpec();
            } else {
                AgentContext.setStructuredOutputSpec(previousStructuredOutputSpec);
            }
        }
    }

    /**
     * 构建子代理规划提示词。
     *
     * @param tools    可用工具列表（逗号拼接）
     * @param maxSteps 最大步骤数
     * @return 系统提示词
     */
    private String buildPlannerPrompt(String tools, int maxSteps) {
        return promptService.subAgentPlannerSystemPrompt(tools, maxSteps);
    }

    /**
     * 收集计划中不在白名单的工具名。
     *
     * @param stepsNode      步骤数组
     * @param toolWhitelist  工具白名单
     * @return 非法工具名列表（去重后）
     */
    private List<String> collectInvalidTools(JsonNode stepsNode, Set<String> toolWhitelist) {
        List<String> invalid = new ArrayList<>();
        for (JsonNode node : stepsNode) {
            String tool = node.path("tool").asText();
            if (!toolWhitelist.contains(tool) && !invalid.contains(tool)) {
                invalid.add(tool);
            }
        }
        return invalid;
    }

    private Map<String, Object> resolveStepPlaceholders(Map<String, Object> rawArgs, List<Map<String, Object>> executedSteps) {
        Map<String, Object> source = rawArgs == null ? Map.of() : rawArgs;
        Map<String, TodoExecutionRecord> context = buildSubAgentAliasContext(executedSteps);
        if (context.isEmpty()) {
            return source;
        }
        return paramResolver.resolve(source, context);
    }

    private Map<String, TodoExecutionRecord> buildSubAgentAliasContext(List<Map<String, Object>> executedSteps) {
        boolean resolveStepAlias = subAgentResolveStepAlias();
        boolean resolveTodoAlias = subAgentResolveTodoAlias();
        if ((!resolveStepAlias && !resolveTodoAlias) || executedSteps == null || executedSteps.isEmpty()) {
            return Map.of();
        }
        Map<String, TodoExecutionRecord> context = new LinkedHashMap<>();
        for (int i = 0; i < executedSteps.size(); i++) {
            Map<String, Object> step = executedSteps.get(i);
            String output = firstNonBlank(step == null ? null : step.get("output"));
            TodoExecutionRecord record = TodoExecutionRecord.builder()
                    .success(Boolean.TRUE.equals(step == null ? null : step.get("success")))
                    .output(output)
                    .summary(preview(output))
                    .toolCallsUsed(1)
                    .build();
            if (resolveStepAlias) {
                context.put("step_" + i, record);
            }
            if (resolveTodoAlias) {
                context.put("todo_" + (i + 1), record);
            }
        }
        return context;
    }

    private List<String> collectUnresolvedPlaceholders(Object value) {
        LinkedHashSet<String> unresolved = new LinkedHashSet<>();
        collectUnresolvedPlaceholders(value, unresolved);
        return new ArrayList<>(unresolved);
    }

    @SuppressWarnings("unchecked")
    private void collectUnresolvedPlaceholders(Object value, Set<String> unresolved) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                collectUnresolvedPlaceholders(entry.getValue(), unresolved);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                collectUnresolvedPlaceholders(item, unresolved);
            }
            return;
        }
        if (!(value instanceof String text) || !text.contains("${")) {
            return;
        }
        Matcher matcher = UNRESOLVED_PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            unresolved.add(matcher.group());
        }
    }

    /**
     * 对步骤参数做兼容性归一化，提升子代理工具调用成功率。
     *
     * @param tool          工具名
     * @param rawArgs       模型原始参数
     * @param executedSteps 已执行步骤（用于回填 dataset_id）
     * @return 归一化参数
     */
    private Map<String, Object> normalizeStepArgs(String tool,
                                                  Map<String, Object> rawArgs,
                                                  List<Map<String, Object>> executedSteps,
                                                  Map<String, Object> seedArgs) {
        Map<String, Object> args = new HashMap<>();
        if (rawArgs != null) {
            args.putAll(rawArgs);
        }

        if ("searchIndex".equals(tool) || "searchStock".equals(tool) || "searchFund".equals(tool)) {
            String keyword = firstNonBlank(args.get("keyword"), args.get("query"), args.get("q"), args.get("name"), args.get("arg0"));
            if (!keyword.isBlank()) {
                args.put("keyword", keyword);
            }
        }

        if ("getIndexDaily".equals(tool) || "getStockDaily".equals(tool)) {
            String tsCode = firstNonBlank(
                    args.get("tsCode"),
                    args.get("ts_code"),
                    args.get("code"),
                    args.get("index_code"),
                    args.get("stock_code"),
                    args.get("arg0")
            );
            String startDateStr = compactDate(firstNonBlank(
                    args.get("startDateStr"),
                    args.get("startDate"),
                    args.get("start_date"),
                    args.get("arg1")
            ));
            String endDateStr = compactDate(firstNonBlank(
                    args.get("endDateStr"),
                    args.get("endDate"),
                    args.get("end_date"),
                    args.get("arg2")
            ));
            if (!tsCode.isBlank()) {
                args.put("tsCode", tsCode);
            }
            if (!startDateStr.isBlank()) {
                args.put("startDateStr", startDateStr);
            }
            if (!endDateStr.isBlank()) {
                args.put("endDateStr", endDateStr);
            }
        }

        if ("executePython".equals(tool)) {
            Map<String, Object> effectiveSeedArgs = seedArgs == null ? Map.of() : seedArgs;
            String seedDatasetId = firstNonBlank(
                    effectiveSeedArgs.get("dataset_id"),
                    effectiveSeedArgs.get("datasetId"),
                    effectiveSeedArgs.get("arg1")
            );
            List<String> seedDatasetIds = parseDatasetIds(firstNonBlank(
                    effectiveSeedArgs.get("dataset_ids"),
                    effectiveSeedArgs.get("datasetIds"),
                    effectiveSeedArgs.get("arg2")
            ));
            List<String> discoveredDatasetIds = extractDatasetIds(executedSteps);

            LinkedHashSet<String> availableDatasetIds = new LinkedHashSet<>();
            if (!seedDatasetId.isBlank()) {
                availableDatasetIds.add(seedDatasetId);
            }
            availableDatasetIds.addAll(seedDatasetIds);
            availableDatasetIds.addAll(discoveredDatasetIds);

            String datasetId = firstNonBlank(args.get("dataset_id"), args.get("datasetId"), args.get("arg1"));
            if (!datasetId.isBlank() && !availableDatasetIds.isEmpty() && !availableDatasetIds.contains(datasetId)) {
                datasetId = availableDatasetIds.iterator().next();
            }
            if (datasetId.isBlank()) {
                datasetId = availableDatasetIds.isEmpty() ? "" : availableDatasetIds.iterator().next();
            }
            if (!datasetId.isBlank()) {
                args.put("dataset_id", datasetId);
            }

            LinkedHashSet<String> mergedDatasetIds = new LinkedHashSet<>();
            if (!datasetId.isBlank()) {
                mergedDatasetIds.add(datasetId);
            }
            mergedDatasetIds.addAll(parseDatasetIds(firstNonBlank(args.get("dataset_ids"), args.get("datasetIds"), args.get("arg2"))));
            mergedDatasetIds.addAll(availableDatasetIds);
            if (!mergedDatasetIds.isEmpty()) {
                args.put("dataset_ids", String.join(",", mergedDatasetIds));
            }

            String code = firstNonBlank(args.get("code"), args.get("arg0"));
            if (code.isBlank()) {
                code = firstNonBlank(effectiveSeedArgs.get("code"), effectiveSeedArgs.get("arg0"));
            }
            if (!code.isBlank()) {
                args.put("code", code);
            }
        }
        return args;
    }

    /**
     * 判断一步工具执行是否可视为成功。
     *
     * @param tool   工具名
     * @param output 工具输出
     * @return true 表示成功
     */
    private boolean isStepSuccessful(String tool, String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        JsonNode root = parseJson(output);
        if (root == null || !root.isObject()) {
            return false;
        }
        return root.path("ok").asBoolean(false);
    }

    /**
     * 从已执行步骤中提取 dataset_id 列表（按出现顺序去重）。
     *
     * @param executedSteps 已执行步骤
     * @return dataset_id 列表
     */
    private List<String> extractDatasetIds(List<Map<String, Object>> executedSteps) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> step : executedSteps) {
            String output = firstNonBlank(step.get("output"));
            JsonNode root = parseJson(output);
            if (root == null || !root.path("ok").asBoolean(false)) {
                continue;
            }
            JsonNode data = root.path("data");
            if (!data.isObject()) {
                continue;
            }
            String datasetId = firstNonBlank(data.path("dataset_id").asText(""));
            if (!datasetId.isBlank()) {
                ids.add(datasetId);
            }
            JsonNode datasetIds = data.path("dataset_ids");
            if (datasetIds.isArray()) {
                for (JsonNode item : datasetIds) {
                    String id = firstNonBlank(item.asText(""));
                    if (!id.isBlank()) {
                        ids.add(id);
                    }
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * 生成 Python 重试节点的 trace 摘要，避免在事件中存放过大文本。
     *
     * @param traces 原始 trace 列表
     * @return 摘要列表
     */
    private List<Map<String, Object>> summarizePythonTraces(List<PythonCodeRefinementNode.AttemptTrace> traces,
                                                            boolean includeLlmSnapshot) {
        if (traces == null || traces.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> summary = new ArrayList<>();
        for (PythonCodeRefinementNode.AttemptTrace trace : traces) {
            Map<String, Object> item = new HashMap<>();
            item.put("attempt", trace.getAttempt());
            item.put("success", trace.isSuccess());
            item.put("code", trace.getCode());
            item.put("code_preview", preview(trace.getCode()));
            item.put("run_args", trace.getRunArgs());
            item.put("run_args_preview", trace.getRunArgs());
            item.put("output_preview", preview(trace.getOutput()));
            if (includeLlmSnapshot && trace.getLlmSnapshot() != null && !trace.getLlmSnapshot().isEmpty()) {
                item.put("llm_snapshot", trace.getLlmSnapshot());
            }
            summary.add(item);
        }
        return summary;
    }

    private Map<String, Object> extractInitialPythonRunArgs(Map<String, Object> args) {
        Map<String, Object> runArgs = new HashMap<>();
        if (args == null) {
            return runArgs;
        }
        Map<String, Object> rawRunArgs = toMap(args.get("run_args"));
        if (!rawRunArgs.isEmpty()) {
            runArgs.putAll(rawRunArgs);
        }

        // 优先使用 dataset_ids（复数），兼容 dataset_id（单数）
        String datasetIds = firstNonBlank(args.get("dataset_ids"), args.get("datasetIds"), args.get("arg2"), args.get("dataset_id"), args.get("datasetId"), args.get("arg1"));
        if (!datasetIds.isBlank()) {
            runArgs.put("dataset_ids", datasetIds);
        }
        String libraries = firstNonBlank(args.get("libraries"), args.get("arg3"));
        if (!libraries.isBlank()) {
            runArgs.put("libraries", libraries);
        }
        Integer timeout = toNullableInt(args.get("timeout_seconds"), args.get("timeoutSeconds"), args.get("arg4"));
        if (timeout != null && timeout > 0) {
            runArgs.put("timeout_seconds", timeout);
        }
        return runArgs;
    }

    private String buildCodingContext(Map<String, Object> args,
                                      List<Map<String, Object>> executedSteps,
                                      String fallbackContext) {
        String modelSelectedContext = firstNonBlank(
                args.get("coding_context"),
                args.get("codingContext"),
                args.get("analysis_context"),
                args.get("analysisContext"),
                args.get("related_context"),
                args.get("relatedContext"),
                args.get("input_context"),
                args.get("inputContext"),
                args.get("context")
        );

        StringBuilder sb = new StringBuilder();
        if (!modelSelectedContext.isBlank()) {
            sb.append("模型指定相关上下文:\n").append(modelSelectedContext.trim()).append("\n");
        } else if (fallbackContext != null && !fallbackContext.isBlank()) {
            sb.append("任务上下文:\n").append(fallbackContext.trim()).append("\n");
        }

        String datasetHints = buildDatasetHints(executedSteps);
        if (!datasetHints.isBlank()) {
            sb.append("最近工具输出中的数据线索:\n").append(datasetHints);
        }
        return sb.toString().trim();
    }

    private String buildDatasetHints(List<Map<String, Object>> executedSteps) {
        if (executedSteps == null || executedSteps.isEmpty()) {
            return "";
        }
        List<String> hints = new ArrayList<>();
        for (Map<String, Object> step : executedSteps) {
            String output = firstNonBlank(step.get("output"));
            if (output.isBlank()) {
                continue;
            }
            JsonNode root = parseJson(output);
            if (root == null || !root.path("ok").asBoolean(false)) {
                continue;
            }
            JsonNode data = root.path("data");
            if (!data.isObject()) {
                continue;
            }

            List<String> datasetIds = new ArrayList<>();
            String datasetId = firstNonBlank(data.path("dataset_id").asText(""));
            if (!datasetId.isBlank()) {
                datasetIds.add(datasetId);
            }
            JsonNode idsNode = data.path("dataset_ids");
            if (idsNode.isArray()) {
                for (JsonNode item : idsNode) {
                    String id = firstNonBlank(item.asText(""));
                    if (!id.isBlank() && !datasetIds.contains(id)) {
                        datasetIds.add(id);
                    }
                }
            }

            if (datasetIds.isEmpty()) {
                continue;
            }

            String tool = firstNonBlank(step.get("tool"));
            Map<String, Object> stepArgs = toMap(step.get("args"));
            String tsCode = firstNonBlank(
                    data.path("ts_code").asText(""),
                    stepArgs.get("tsCode"),
                    stepArgs.get("ts_code"),
                    stepArgs.get("code")
            );
            String rows = data.path("rows").asText("");
            String startDate = data.path("start_date").asText("");
            String endDate = data.path("end_date").asText("");
            String fields = data.path("fields").isArray() ? data.path("fields").toString() : "";

            for (String id : datasetIds) {
                String line = "- dataset_id=" + id
                        + (tool.isBlank() ? "" : " (tool=" + tool + ")")
                        + (tsCode.isBlank() ? "" : " (tsCode=" + tsCode + ")")
                        + (rows.isBlank() ? "" : " (rows=" + rows + ")")
                        + ((startDate.isBlank() || endDate.isBlank()) ? "" : " (range=" + startDate + "~" + endDate + ")")
                        + (fields.isBlank() ? "" : " (fields=" + fields + ")");
                if (!hints.contains(line)) {
                    hints.add(line);
                }
            }
        }
        return String.join("\n", hints);
    }

    private JsonNode parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Collections.emptyMap();
    }

    private Integer toNullableInt(Object... values) {
        String raw = firstNonBlank(values);
        if (raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<String> parseDatasetIds(String datasetIds) {
        if (datasetIds == null || datasetIds.isBlank()) {
            return List.of();
        }
        String raw = datasetIds.trim();
        if (raw.startsWith("[") && raw.endsWith("]")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String value = nvl(part).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1).trim();
            }
            if (!value.isBlank()) {
                ids.add(value);
            }
        }
        return new ArrayList<>(ids);
    }

    private int resolveSubAgentPlanningMaxAttempts() {
        int local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getSubAgent)
                .map(AgentLlmProperties.SubAgent::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getMaxAttempts)
                .orElse(0);
        if (local > 0) {
            return Math.max(1, Math.min(local, 10));
        }
        int base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getSubAgent)
                .map(AgentLlmProperties.SubAgent::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getMaxAttempts)
                .orElse(0);
        if (base > 0) {
            return Math.max(1, Math.min(base, 10));
        }
        return MAX_PLAN_ATTEMPTS;
    }

    private boolean subAgentStructuredEnabled() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getSubAgent)
                .map(AgentLlmProperties.SubAgent::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getEnabled);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getSubAgent)
                .map(AgentLlmProperties.SubAgent::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getEnabled)
                .orElse(null);
        return base == null || base;
    }

    private boolean subAgentStructuredStrict() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getSubAgent)
                .map(AgentLlmProperties.SubAgent::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrict);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getSubAgent)
                .map(AgentLlmProperties.SubAgent::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrict)
                .orElse(null);
        return base == null || base;
    }

    private boolean subAgentRequireProviderParameters() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getSubAgent)
                .map(AgentLlmProperties.SubAgent::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getRequireProviderParameters);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getSubAgent)
                .map(AgentLlmProperties.SubAgent::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getRequireProviderParameters)
                .orElse(null);
        return base == null || base;
    }

    private boolean subAgentAllowProviderFallbacks() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getSubAgent)
                .map(AgentLlmProperties.SubAgent::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getAllowProviderFallbacks);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getSubAgent)
                .map(AgentLlmProperties.SubAgent::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getAllowProviderFallbacks)
                .orElse(null);
        return base != null && base;
    }

    private boolean subAgentResolveStepAlias() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getSubAgent)
                .map(AgentLlmProperties.SubAgent::getPlaceholder)
                .map(AgentLlmProperties.Placeholder::getResolveStepAlias);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getSubAgent)
                .map(AgentLlmProperties.SubAgent::getPlaceholder)
                .map(AgentLlmProperties.Placeholder::getResolveStepAlias)
                .orElse(null);
        return base == null || base;
    }

    private boolean subAgentResolveTodoAlias() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getSubAgent)
                .map(AgentLlmProperties.SubAgent::getPlaceholder)
                .map(AgentLlmProperties.Placeholder::getResolveTodoAlias);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getSubAgent)
                .map(AgentLlmProperties.SubAgent::getPlaceholder)
                .map(AgentLlmProperties.Placeholder::getResolveTodoAlias)
                .orElse(null);
        return base == null || base;
    }

    private String compactDate(String raw) {
        if (raw == null) {
            return "";
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 8 || digits.length() == 13) {
            return digits;
        }
        return raw.trim();
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String s = String.valueOf(value).trim();
            if (!s.isBlank()) {
                return s;
            }
        }
        return "";
    }

    /**
     * 输出子代理事件（参数缺失时静默跳过，避免污染主流程）。
     *
     * @param request   子代理请求
     * @param eventType 事件类型
     * @param payload   事件负载
     */
    private void emitEvent(SubAgentRequest request, String eventType, Object payload) {
        if (request == null || request.getRunId() == null || request.getRunId().isBlank()) {
            return;
        }
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            return;
        }
        eventService.append(request.getRunId(), request.getUserId(), eventType, payload);
    }

    /**
     * 将步骤 JSON 生成简要摘要，供事件与前端展示使用。
     *
     * @param stepsNode 步骤数组节点
     * @return 步骤摘要
     */
    private List<Map<String, Object>> buildStepSummary(JsonNode stepsNode) {
        List<Map<String, Object>> summary = new ArrayList<>();
        int idx = 0;
        for (JsonNode node : stepsNode) {
            Map<String, Object> item = new HashMap<>();
            item.put("index", idx++);
            item.put("tool", node.path("tool").asText());
            item.put("note", node.path("note").asText());
            summary.add(item);
        }
        return summary;
    }

    /**
     * 裁剪长文本，避免事件负载过大。
     *
     * @param text 原始文本
     * @return 预览文本
     */
    private String preview(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > 300) {
            return text.substring(0, 300);
        }
        return text;
    }

    /**
     * 空值转空字符串。
     *
     * @param value 原始字符串
     * @return 非空字符串
     */
    private String nvl(String value) {
        return value == null ? "" : value;
    }
}

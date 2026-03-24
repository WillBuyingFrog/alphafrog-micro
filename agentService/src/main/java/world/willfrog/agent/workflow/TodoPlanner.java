package world.willfrog.agent.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.service.AgentLlmRequestSnapshotBuilder;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentMessageService;
import world.willfrog.agent.service.AgentContextCompressor;
import world.willfrog.agent.service.ReactConversationContext;
import world.willfrog.agent.entity.AgentRunMessage;
import world.willfrog.agent.service.AgentRunStateStore;
import world.willfrog.agent.context.AgentContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class TodoPlanner {

    private final AgentPromptService promptService;
    private final AgentEventService eventService;
    private final AgentRunStateStore stateStore;
    private final AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    private final AgentObservabilityService observabilityService;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final AgentLlmProperties llmProperties;
    private final AgentMessageService messageService;
    private final AgentContextCompressor contextCompressor;
    private final ObjectMapper objectMapper;

    @Value("${agent.flow.workflow.max-todos:10}")
    private int defaultMaxTodos;

    public TodoPlan plan(PlanRequest request) {
        AgentRun run = request.getRun();
        String runId = run.getId();
        String userId = request.getUserId();

        eventService.append(runId, userId, "PLANNING_STARTED", Map.of("run_id", runId));

        try {
            boolean override = stateStore.isPlanOverride(runId);
            TodoPlan todoPlan = null;
            Optional<String> stateStoredPlan = stateStore.loadPlan(runId);
            if (stateStoredPlan.isPresent()) {
                todoPlan = parsePlan(stateStoredPlan.get());
            } else if (run.getPlanJson() != null && !run.getPlanJson().isBlank() && !"{}".equals(run.getPlanJson().trim())) {
                todoPlan = parsePlan(run.getPlanJson());
            }

            Set<String> toolWhitelist = request.getToolSpecifications().stream()
                    .map(ToolSpecification::name)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            if (todoPlan == null || todoPlan.getItems().isEmpty()) {
                todoPlan = generatePlan(request, toolWhitelist);
            }

            todoPlan = normalize(todoPlan, resolveMaxTodos(request), toolWhitelist);
            if (todoPlan.getItems().isEmpty()) {
                throw new IllegalStateException("todo_plan_empty");
            }

            // 设置执行模式（从请求传入，用于 DAG/Linear 选择）
            PlanExecutionMode mode = request.getExecutionMode();
            if (mode == null) {
                mode = PlanExecutionMode.AUTO;
            }
            todoPlan.setExecutionMode(mode);

            String planJson = safeWrite(todoPlan);
            stateStore.recordPlan(runId, planJson, true);
            if (override) {
                stateStore.clearPlanOverride(runId);
            }

            eventService.append(runId, userId, "TODO_LIST_CREATED", Map.of(
                    "items_count", todoPlan.getItems().size(),
                    "plan", planJson
            ));
            eventService.append(runId, userId, "PLANNING_COMPLETED", Map.of(
                    "items_count", todoPlan.getItems().size(),
                    "itemsCount", todoPlan.getItems().size(),
                    "endpoint", nvl(request.getEndpointName()),
                    "model", nvl(request.getModelName())
            ));
            return todoPlan;
        } catch (Exception e) {
            String reason = nvl(e.getMessage()).isBlank() ? e.getClass().getSimpleName() : nvl(e.getMessage());
            eventService.append(runId, userId, "PLANNING_FAILED", Map.of("error", reason));
            throw new IllegalStateException(reason, e);
        }
    }

    private TodoPlan generatePlan(PlanRequest request, Set<String> toolWhitelist) {
        return generatePlanWithRetries(request, toolWhitelist);
    }

    private TodoPlan generatePlanWithRetries(PlanRequest request, Set<String> toolWhitelist) {
        String runId = request.getRun().getId();
        boolean structuredEnabled = planningStructuredEnabled();
        int maxAttempts = resolvePlanningMaxAttempts();
        int maxTodos = resolveMaxTodos(request);
        String lastCategory = "";
        String lastError = "";
        observabilityService.markPlanningStructured(runId, structuredEnabled);
        observabilityService.setLastPlanningErrorCategory(runId, "");

        String toolList = toolWhitelist.stream().sorted().collect(Collectors.joining(", "));
        String reactSystem = promptService.reactSystemPrompt();
        String analysisStage = promptService.planningAnalysisStageInstruction(toolList, maxTodos);
        String structuredStage = promptService.planningStructuredStageInstruction();
        String dynamicPrefix = promptService.dynamicContextPrefix();
        String dialogueContext = buildDialogueContext(runId, request.getUserGoal());
        String dagModeGuidance = shouldInjectDagGuidance(request.getExecutionMode())
                ? nvl(promptService.dagModeGuidancePrompt())
                : "";

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            observabilityService.incrementPlanningAttempts(runId, false);

            // ─── ReAct 累积式上下文：两步规划共享同一 context ───
            ReactConversationContext ctx = new ReactConversationContext();
            ctx.setSystemMessage(reactSystem);

            // ── Step 1：自然语言分析 ──
            String analysisContent;
            if (dialogueContext.isBlank()) {
                analysisContent = dynamicPrefix + "\n" + analysisStage + "\n\n用户需求：" + request.getUserGoal();
            } else {
                analysisContent = dynamicPrefix + "\n" + analysisStage + "\n\n"
                        + "历史对话压缩内容：\n" + dialogueContext
                        + "\n\n当前轮次用户需求：" + request.getUserGoal()
                        + "\n\n请参考历史对话，以当前轮次用户需求为重点，先分析规划思路。";
            }
            if (!dagModeGuidance.isBlank()) {
                analysisContent = analysisContent + "\n\n[DAG 模式选择指引]\n" + dagModeGuidance;
            }
            ctx.addUserMessage(analysisContent);

            String previousStage = AgentContext.getStage();
            AgentContext.StructuredOutputSpec previousSpec = AgentContext.getStructuredOutputSpec();
            AgentContext.setPhase(AgentObservabilityService.PHASE_PLANNING);
            AgentContext.setStage("todo_planning_analysis");
            AgentContext.clearStructuredOutputSpec();

            // 设置 Planning 阶段 reasoning 配置
            String planningReasoningEffort = resolvePlanningReasoningEffort();
            if (planningReasoningEffort != null) {
                AgentContext.setReasoningEffort(planningReasoningEffort);
            }

            ChatResponse analysisResponse;
            String analysisText;
            ChatResponse structuredResponse;
            String raw;
            String planningTraceId;
            try {
                // ── Step 1 LLM call ──
                long analysisStartedAt = System.currentTimeMillis();
                analysisResponse = request.getModel().chat(ctx.getMessages());
                analysisText = analysisResponse.aiMessage() == null ? "" : nvl(analysisResponse.aiMessage().text());
                long analysisCompletedAt = System.currentTimeMillis();

                Map<String, Object> analysisSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                        request.getEndpointName(),
                        request.getEndpointBaseUrl(),
                        request.getModelName(),
                        ctx.getMessages(),
                        request.getToolSpecifications(),
                        Map.of("stage", "todo_planning_analysis", "attempt", attempt)
                );
                observabilityService.recordLlmCall(
                        runId,
                        AgentObservabilityService.PHASE_PLANNING,
                        analysisResponse.metadata() != null ? analysisResponse.metadata().tokenUsage() : null,
                        analysisCompletedAt - analysisStartedAt,
                        analysisStartedAt,
                        analysisCompletedAt,
                        request.getEndpointName(),
                        request.getModelName(),
                        null,
                        analysisSnapshot,
                        analysisText
                );

                ctx.addAssistantMessage(analysisText);

                // ── Step 2：结构化 JSON 转换 ──
                ctx.addUserMessage(structuredStage);

                AgentContext.setStage("todo_planning");
                if (structuredEnabled) {
                    AgentContext.setStructuredOutputSpec(new AgentContext.StructuredOutputSpec(
                            "todo_plan",
                            planningStructuredStrict(),
                            StructuredPlanningSupport.todoPlanningJsonSchema(),
                            planningRequireProviderParameters(),
                            planningAllowProviderFallbacks()
                    ));
                } else {
                    AgentContext.clearStructuredOutputSpec();
                }

                // ── Step 2 LLM call ──
                long structuredStartedAt = System.currentTimeMillis();
                structuredResponse = request.getModel().chat(ctx.getMessages());
                raw = structuredResponse.aiMessage() == null ? "" : nvl(structuredResponse.aiMessage().text());
                Map<String, Object> structuredSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                        request.getEndpointName(),
                        request.getEndpointBaseUrl(),
                        request.getModelName(),
                        ctx.getMessages(),
                        request.getToolSpecifications(),
                        Map.of(
                                "stage", "todo_planning",
                                "attempt", attempt,
                                "structured_output", structuredEnabled
                        )
                );
                long structuredCompletedAt = System.currentTimeMillis();
                planningTraceId = observabilityService.recordLlmCall(
                        runId,
                        AgentObservabilityService.PHASE_PLANNING,
                        structuredResponse.metadata() != null ? structuredResponse.metadata().tokenUsage() : null,
                        structuredCompletedAt - structuredStartedAt,
                        structuredStartedAt,
                        structuredCompletedAt,
                        request.getEndpointName(),
                        request.getModelName(),
                        null,
                        structuredSnapshot,
                        raw
                );
            } finally {
                if (previousStage == null || previousStage.isBlank()) {
                    AgentContext.clearStage();
                } else {
                    AgentContext.setStage(previousStage);
                }
                if (previousSpec == null) {
                    AgentContext.clearStructuredOutputSpec();
                } else {
                    AgentContext.setStructuredOutputSpec(previousSpec);
                }
                AgentContext.clearReasoningEffort();
            }

            try {
                JsonNode root = StructuredPlanningSupport.parseStructuredJson(objectMapper, raw);
                StructuredPlanningSupport.ValidationResult validation = StructuredPlanningSupport.validateTodoPlan(root, maxTodos, toolWhitelist);
                if (!validation.valid()) {
                    throw new StructuredPlanningSupport.StructuredPlanningException(validation.category(), validation.message());
                }
                TodoPlan todoPlan = parsePlanNode(root);
                todoPlan.setAnalysis(analysisText);
                observabilityService.setLastPlanningErrorCategory(runId, "");
                return todoPlan;
            } catch (StructuredPlanningSupport.StructuredPlanningException e) {
                lastCategory = nvl(e.category());
                lastError = nvl(e.getMessage());
                observabilityService.setLastPlanningErrorCategory(runId, lastCategory);
                eventService.append(runId, request.getUserId(), "PLANNING_RETRY", Map.of(
                        "attempt", attempt,
                        "max_attempts", maxAttempts,
                        "error_category", nvl(lastCategory),
                        "error", nvl(lastError)
                ));
            }
        }
        if (planningFailOnExhaustedRetries()) {
            throw new IllegalStateException("planning_retry_exhausted:"
                    + nvl(lastCategory) + ":" + nvl(lastError));
        }
        return TodoPlan.builder().analysis("").items(List.of()).build();
    }

    private TodoPlan normalize(TodoPlan source, int maxTodos, Set<String> toolWhitelist) {
        TodoPlan out = new TodoPlan();
        out.setAnalysis(nvl(source.getAnalysis()));
        List<TodoItem> normalized = new ArrayList<>();
        int seq = 1;
        for (TodoItem raw : source.getItems() == null ? List.<TodoItem>of() : source.getItems()) {
            if (raw == null) {
                continue;
            }
            if (normalized.size() >= maxTodos) {
                break;
            }
            String id = nvl(raw.getId());
            if (id.isBlank()) {
                id = "todo_" + seq;
            }

            TodoItem item = TodoItem.builder()
                    .id(id)
                    .sequence(seq)
                    .description(nvl(raw.getDescription()))
                    .dependsOn(raw.getDependsOn() == null ? List.of() : raw.getDependsOn())
                    .groupKey(nvl(raw.getGroupKey()))
                    .parallelizable(raw.isParallelizable())
                    .status(TodoStatus.PENDING)
                    .createdAt(Instant.now())
                    .build();
            normalized.add(item);
            seq++;
        }

        out.setItems(normalized);
        return out;
    }

    private TodoPlan parsePlan(String planJson) {
        if (planJson == null || planJson.isBlank()) {
            return TodoPlan.builder().analysis("").items(List.of()).build();
        }
        try {
            JsonNode root = objectMapper.readTree(planJson);
            return parsePlanNode(root);
        } catch (Exception e) {
            return TodoPlan.builder().analysis("").items(List.of()).build();
        }
    }

    private TodoPlan parsePlanNode(JsonNode root) {
        if (root == null || !root.isObject()) {
            return TodoPlan.builder().analysis("").items(List.of()).build();
        }
        JsonNode itemsNode = root.path("items");
        if (!itemsNode.isArray()) {
            itemsNode = root.path("todos");
        }

        List<TodoItem> items = new ArrayList<>();
        if (itemsNode.isArray()) {
            int seq = 1;
            for (JsonNode node : itemsNode) {
                // 解析依赖关系（DAG 模式下可能存在）
                List<String> dependsOn = new ArrayList<>();
                JsonNode dependsOnNode = node.path("dependsOn");
                if (dependsOnNode.isArray()) {
                    for (JsonNode depNode : dependsOnNode) {
                        dependsOn.add(depNode.asText());
                    }
                }

                TodoItem item = TodoItem.builder()
                        .id(nvl(node.path("id").asText("")))
                        .sequence(node.path("sequence").asInt(seq))
                        .description(nvl(node.path("description").asText("")))
                        .dependsOn(dependsOn)
                        .groupKey(nvl(node.path("groupKey").asText("")))
                        .parallelizable(node.path("parallelizable").asBoolean(false))
                        .status(TodoStatus.PENDING)
                        .createdAt(Instant.now())
                        .build();
                items.add(item);
                seq++;
            }
        }

        return TodoPlan.builder()
                .analysis(nvl(root.path("analysis").asText("")))
                .items(items)
                .build();
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    private TodoType parseType(String text) {
        try {
            return TodoType.valueOf(nvl(text).trim().toUpperCase());
        } catch (Exception e) {
            return TodoType.TOOL_CALL;
        }
    }

    private ExecutionMode parseExecutionMode(String text) {
        try {
            return ExecutionMode.valueOf(nvl(text).trim().toUpperCase());
        } catch (Exception e) {
            return ExecutionMode.AUTO;
        }
    }

    private int resolveMaxTodos(PlanRequest request) {
        // 1. 客户端传入了 maxTodos：先做 cap 校验，超限则立即拒绝
        if (request != null && request.getMaxTodos() != null && request.getMaxTodos() > 0) {
            int requested = request.getMaxTodos();
            int cap = resolveMaxTodosClientCap();
            if (cap > 0 && requested > cap) {
                throw new IllegalArgumentException(
                        "max_todos_exceeds_server_cap:requested=" + requested + ",cap=" + cap);
            }
            return clamp(requested, 1, 50);
        }
        // 2. 本地配置文件（热加载）
        int local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getMaxTodos)
                .orElse(0);
        if (local > 0) {
            return clamp(local, 1, 50);
        }
        // 3. application.yml / 静态 bean
        int base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getMaxTodos)
                .orElse(0);
        if (base > 0) {
            return clamp(base, 1, 50);
        }
        return clamp(defaultMaxTodos, 1, 50);
    }

    private int resolveMaxTodosClientCap() {
        int local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getMaxTodosClientCap)
                .orElse(0);
        if (local > 0) {
            return local;
        }
        return Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getMaxTodosClientCap)
                .orElse(0);
    }

    private boolean planningStructuredEnabled() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getEnabled);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getEnabled)
                .orElse(null);
        return base == null || base;
    }

    private int resolvePlanningMaxAttempts() {
        int local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getMaxAttempts)
                .orElse(0);
        if (local > 0) {
            return clamp(local, 1, 10);
        }
        int base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getMaxAttempts)
                .orElse(0);
        if (base > 0) {
            return clamp(base, 1, 10);
        }
        return 3;
    }

    private boolean planningStructuredStrict() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrict);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrict)
                .orElse(null);
        return base == null || base;
    }

    private boolean planningFailOnExhaustedRetries() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getFailOnExhaustedRetries);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getFailOnExhaustedRetries)
                .orElse(null);
        return base == null || base;
    }

    private boolean planningRequireProviderParameters() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getRequireProviderParameters);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getRequireProviderParameters)
                .orElse(null);
        return base == null || base;
    }

    private boolean planningAllowProviderFallbacks() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getAllowProviderFallbacks);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getAllowProviderFallbacks)
                .orElse(null);
        return base != null && base;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean shouldInjectDagGuidance(PlanExecutionMode mode) {
        if (mode == null) {
            return true;
        }
        return mode == PlanExecutionMode.AUTO || mode == PlanExecutionMode.DAG;
    }

    private String safeWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    /**
     * 解析 Planning 阶段的 OpenRouter reasoning (thinking) 配置。
     * <p>优先从热加载配置读取，其次从静态配置读取。</p>
     *
     * @return reasoning effort 值，或 null 表示不配置（使用模型默认行为）
     */
    private String resolvePlanningReasoningEffort() {
        // 1. 尝试从 local config (热加载) 读取
        String effort = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getReasoning)
                .map(AgentLlmProperties.Reasoning::resolveEffort)
                .orElse(null);
        if (effort != null) return effort;

        // 2. 从 base properties 读取
        if (llmProperties.getRuntime() != null && llmProperties.getRuntime().getPlanning() != null
                && llmProperties.getRuntime().getPlanning().getReasoning() != null) {
            return llmProperties.getRuntime().getPlanning().getReasoning().resolveEffort();
        }
        return null;
    }

    /**
     * 构建对话上下文（用于多轮对话）。
     * <p>
     * 1. 加载消息历史
     * 2. 应用上下文压缩
     * 3. 格式化为对话文本
     *
     * @param runId Run ID
     * @return 对话上下文文本（空字符串表示没有历史消息）
     */
    private String buildDialogueContext(String runId, String currentUserGoal) {
        try {
            List<AgentRunMessage> messages = messageService.listMessages(runId);
            if (messages == null || messages.isEmpty()) {
                return "";
            }

            AgentContextCompressor.ContextBuildResult result = contextCompressor.buildCompressedContext(messages, currentUserGoal);
            return result.text();
        } catch (Exception e) {
            log.warn("Failed to build dialogue context for runId={}, ignoring: {}", runId, e.getMessage());
            return "";
        }
    }

    @Data
    @Builder
    public static class PlanRequest {
        private AgentRun run;
        private String userId;
        private String userGoal;
        private ChatModel model;
        private List<ToolSpecification> toolSpecifications;
        private String endpointName;
        private String endpointBaseUrl;
        private String modelName;
        private PlanExecutionMode executionMode;
        /** 客户端可选覆盖：本次 run 最多规划几个 todo（null 则使用服务端配置）。 */
        private Integer maxTodos;
    }
}

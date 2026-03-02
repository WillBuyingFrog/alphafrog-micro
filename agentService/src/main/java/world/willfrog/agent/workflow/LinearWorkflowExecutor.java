package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.graph.SubAgentRunner;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentCreditService;
import world.willfrog.agent.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.service.AgentLlmRequestSnapshotBuilder;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentRunStateStore;
import world.willfrog.agent.service.AgentMessageService;
import world.willfrog.agent.service.AgentContextCompressor;
import world.willfrog.agent.service.AgentAiServiceFactory;
import world.willfrog.agent.entity.AgentRunMessage;
import world.willfrog.agent.tool.ToolRouter;

import java.time.Instant;
import java.util.ArrayList;
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
public class LinearWorkflowExecutor implements WorkflowExecutor {
    private static final Pattern UNRESOLVED_PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{[^}]+}");

    private final AgentEventService eventService;
    private final AgentPromptService promptService;
    private final ToolRouter toolRouter;
    private final SubAgentRunner subAgentRunner;
    private final TodoParamResolver paramResolver;
    private final ToolCallCounter toolCallCounter;
    private final AgentRunStateStore stateStore;
    private final AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    private final AgentObservabilityService observabilityService;
    private final AgentCreditService creditService;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final AgentLlmProperties llmProperties;
    private final AgentMessageService messageService;
    private final AgentContextCompressor contextCompressor;
    private final AgentAiServiceFactory aiServiceFactory;
    private final PythonStaticPrecheckService pythonStaticPrecheckService;
    private final PythonSemanticJudgeService pythonSemanticJudgeService;
    private final ObjectMapper objectMapper;

    @Value("${agent.flow.workflow.max-tool-calls:20}")
    private int defaultMaxToolCalls;

    @Value("${agent.flow.workflow.max-tool-calls-per-sub-agent:10}")
    private int defaultMaxToolCallsPerSubAgent;

    @Value("${agent.flow.workflow.fail-fast:false}")
    private boolean defaultFailFast;

    @Value("${agent.flow.workflow.default-execution-mode:AUTO}")
    private String defaultExecutionMode;

    @Value("${agent.flow.workflow.sub-agent-enabled:true}")
    private boolean defaultSubAgentEnabled;

    @Value("${agent.flow.workflow.sub-agent-max-steps:6}")
    private int defaultSubAgentMaxSteps;

    @Value("${agent.flow.workflow.max-retries-per-todo:3}")
    private int defaultMaxRetriesPerTodo;

    @Override
    public WorkflowExecutionResult execute(WorkflowRequest request) {
        AgentRun run = request.getRun();
        String runId = run.getId();
        String userId = request.getUserId();
        WorkflowConfig config = resolveConfig();

        WorkflowState state = stateStore.loadWorkflowState(runId)
                .orElseGet(() -> WorkflowState.builder()
                        .currentIndex(0)
                        .completedItems(new ArrayList<>())
                        .context(new LinkedHashMap<>())
                        .toolCallsUsed(0)
                        .savedAt(Instant.now())
                        .build());

        toolCallCounter.reset(runId);
        toolCallCounter.set(runId, state.getToolCallsUsed());

        List<TodoItem> items = request.getTodoPlan().getItems() == null ? List.of() : request.getTodoPlan().getItems();
        List<TodoItem> completed = new ArrayList<>(state.getCompletedItems());
        List<TodoItem> allProcessedItems = new ArrayList<>(completed);
        Map<String, TodoExecutionRecord> context = new LinkedHashMap<>(state.getContext());
        boolean hasFailure = false;

        for (int idx = Math.max(0, state.getCurrentIndex()); idx < items.size(); idx++) {
            if (!eventService.isRunnable(runId, userId)) {
                WorkflowState pausedState = WorkflowState.builder()
                        .currentIndex(idx)
                        .completedItems(completed)
                        .context(context)
                        .toolCallsUsed(toolCallCounter.get(runId))
                        .savedAt(Instant.now())
                        .build();
                stateStore.saveWorkflowState(runId, pausedState);
                eventService.append(runId, userId, "WORKFLOW_PAUSED", Map.of(
                        "current_index", idx,
                        "tool_calls_used", toolCallCounter.get(runId)
                ));
                return WorkflowExecutionResult.builder()
                        .paused(true)
                        .success(false)
                        .failureReason("")
                        .finalAnswer("")
                        .completedItems(allProcessedItems)
                        .context(context)
                        .toolCallsUsed(toolCallCounter.get(runId))
                        .build();
            }

            TodoItem item = items.get(idx);
            TodoExecutionRecord record = executeTodoWithRetry(request, item, context, allProcessedItems, config);
            item.setCompletedAt(Instant.now());
            item.setResultSummary(nvl(record.getSummary()));
            item.setOutput(nvl(record.getOutput()));

            if (record.isSuccess()) {
                item.setStatus(TodoStatus.COMPLETED);
                completed.add(item);
                context.put(item.getId(), record);
                eventService.append(runId, userId, "TODO_FINISHED", Map.of(
                        "todo_id", nvl(item.getId()),
                        "success", true,
                        "summary", nvl(record.getSummary()),
                        "output_preview", preview(record.getOutput()),
                        "tool_calls_used", toolCallCounter.get(runId)
                ));
            } else {
                item.setStatus(TodoStatus.FAILED);
                hasFailure = true;
                eventService.append(runId, userId, "TODO_FAILED", Map.of(
                        "todo_id", nvl(item.getId()),
                        "success", false,
                        "summary", nvl(record.getSummary()),
                        "output_preview", preview(record.getOutput()),
                        "tool_calls_used", toolCallCounter.get(runId)
                ));
            }
            allProcessedItems.add(item);

            WorkflowState checkpoint = WorkflowState.builder()
                    .currentIndex(idx + 1)
                    .completedItems(completed)
                    .context(context)
                    .toolCallsUsed(toolCallCounter.get(runId))
                    .savedAt(Instant.now())
                    .build();
            stateStore.saveWorkflowState(runId, checkpoint);

            if (!record.isSuccess() && config.failFast()) {
                String finalAnswer = generateFinalAnswer(request, allProcessedItems, context);
                return WorkflowExecutionResult.builder()
                        .paused(false)
                        .success(false)
                        .failureReason("todo_failed:" + nvl(item.getId()))
                        .finalAnswer(finalAnswer)
                        .completedItems(allProcessedItems)
                        .context(context)
                        .toolCallsUsed(toolCallCounter.get(runId))
                        .build();
            }
        }

        stateStore.clearWorkflowState(runId);
        String finalAnswer = generateFinalAnswer(request, allProcessedItems, context);
        if (hasFailure) {
            return WorkflowExecutionResult.builder()
                    .paused(false)
                    .success(false)
                    .failureReason("todo_partial_failed")
                    .finalAnswer(finalAnswer)
                    .completedItems(allProcessedItems)
                    .context(context)
                    .toolCallsUsed(toolCallCounter.get(runId))
                    .build();
        }
        return WorkflowExecutionResult.builder()
                .paused(false)
                .success(true)
                .failureReason("")
                .finalAnswer(finalAnswer)
                .completedItems(allProcessedItems)
                .context(context)
                .toolCallsUsed(toolCallCounter.get(runId))
                .build();
    }

    private TodoExecutionRecord executeTodoWithRetry(WorkflowRequest request,
                                                     TodoItem item,
                                                     Map<String, TodoExecutionRecord> context,
                                                     List<TodoItem> allProcessedItems,
                                                     WorkflowConfig config) {
        String runId = request.getRun().getId();
        String userId = request.getUserId();
        TodoType type = item.getType() == null ? TodoType.TOOL_CALL : item.getType();

        if (type == TodoType.THOUGHT || type == TodoType.SUB_AGENT) {
            item.setStatus(TodoStatus.RUNNING);
            eventService.append(runId, userId, "TODO_STARTED", Map.of(
                    "todo_id", nvl(item.getId()),
                    "sequence", item.getSequence(),
                    "type", type.name(),
                    "tool", nvl(item.getToolName())
            ));
            return executeItem(request, item, context, config);
        }

        int attempt = 0;
        int totalRecoveryAttempts = 0;
        int staticRecoveryAttempts = 0;
        int runtimeRecoveryAttempts = 0;
        int semanticRecoveryAttempts = 0;
        TodoExecutionRecord record;
        while (true) {
            item.setStatus(TodoStatus.RUNNING);
            eventService.append(runId, userId, "TODO_STARTED", Map.of(
                    "todo_id", nvl(item.getId()),
                    "sequence", item.getSequence(),
                    "type", type.name(),
                    "tool", nvl(item.getToolName()),
                    "attempt", attempt + 1
            ));

            record = executeItem(request, item, context, config);

            if (record.isSuccess()) {
                return record;
            }

            attempt++;
            TodoFailureCategory category = resolveFailureCategory(record);
            if (!canRetryByCategory(category, staticRecoveryAttempts, runtimeRecoveryAttempts, semanticRecoveryAttempts, totalRecoveryAttempts, config)) {
                log.debug("Todo {} retry budget exhausted, category={}, static={}, runtime={}, semantic={}, total={}",
                        item.getId(), category, staticRecoveryAttempts, runtimeRecoveryAttempts, semanticRecoveryAttempts, totalRecoveryAttempts);
                return record;
            }
            if (attempt >= config.maxRetriesPerTodo()) {
                log.debug("Todo {} failed after {} attempts, giving up", item.getId(), attempt);
                return record;
            }
            if (toolCallCounter.isLimitReached(runId, config.maxToolCalls())) {
                log.debug("Tool call limit reached, cannot retry todo {}", item.getId());
                return record;
            }

            Map<String, Object> recovery = requestRecoveryParams(request, item, record, context, config);
            if (recovery == null) {
                return record;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> newParams = (Map<String, Object>) recovery.get("params");
            if (newParams == null || newParams.isEmpty()) {
                return record;
            }

            item.setParams(newParams);
            totalRecoveryAttempts++;
            switch (category) {
                case STATIC -> staticRecoveryAttempts++;
                case SEMANTIC -> semanticRecoveryAttempts++;
                default -> runtimeRecoveryAttempts++;
            }
            observabilityService.incrementRecoveryAttempt(runId, category.name());
            eventService.append(runId, userId, "TODO_RETRY", Map.of(
                    "todo_id", nvl(item.getId()),
                    "attempt", attempt + 1,
                    "tool_calls_used", toolCallCounter.get(runId),
                    "failure_category", category.name(),
                    "static_recovery_attempts", staticRecoveryAttempts,
                    "runtime_recovery_attempts", runtimeRecoveryAttempts,
                    "semantic_recovery_attempts", semanticRecoveryAttempts,
                    "total_recovery_attempts", totalRecoveryAttempts
            ));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requestRecoveryParams(WorkflowRequest request,
                                                      TodoItem item,
                                                      TodoExecutionRecord failedRecord,
                                                      Map<String, TodoExecutionRecord> context,
                                                      WorkflowConfig config) {
        String runId = request.getRun().getId();
        String userId = request.getUserId();
        TodoFailureCategory failureCategory = resolveFailureCategory(failedRecord);

        eventService.append(runId, userId, "TODO_RECOVERY_STARTED", Map.of(
                "todo_id", nvl(item.getId()),
                "tool", nvl(item.getToolName()),
                "error_preview", preview(failedRecord.getSummary()),
                "error_category", failureCategory.name()
        ));

        Map<String, Object> userPayload = new LinkedHashMap<>();
        userPayload.put("user_goal", nvl(request.getUserGoal()));
        userPayload.put("failed_todo", Map.of(
                "id", nvl(item.getId()),
                "tool", nvl(item.getToolName()),
                "params", item.getParams() == null ? Map.of() : item.getParams(),
                "reasoning", nvl(item.getReasoning()),
                "error", nvl(failedRecord.getSummary()),
                "error_category", failureCategory.name(),
                "precheck_report", failedRecord.getPrecheckReport() == null ? Map.of() : failedRecord.getPrecheckReport(),
                "semantic_judge_report", failedRecord.getSemanticJudgeReport() == null ? Map.of() : failedRecord.getSemanticJudgeReport()
        ));
        userPayload.put("context", context == null ? Map.of() : context);

        List<ChatMessage> messages = List.of(
                new SystemMessage(promptService.workflowTodoRecoverySystemPrompt()),
                new UserMessage(safeWrite(userPayload))
        );
        RecoveryModelSelection recoveryModel = resolveRecoveryModel(request, failedRecord, config);

        // 设置当前 phase 并记录开始时间
        String previousStage = AgentContext.getStage();
        AgentContext.setPhase(AgentObservabilityService.PHASE_SUMMARIZING);
        AgentContext.setStage("workflow_todo_recovery");
        long llmStartedAt = System.currentTimeMillis();
        Response<AiMessage> response;
        try {
            response = recoveryModel.model().generate(messages);
        } finally {
            if (previousStage == null || previousStage.isBlank()) {
                AgentContext.clearStage();
            } else {
                AgentContext.setStage(previousStage);
            }
        }
        long llmCompletedAt = System.currentTimeMillis();
        long llmDurationMs = llmCompletedAt - llmStartedAt;
        String text = response.content() == null ? "" : nvl(response.content().text());

        Map<String, Object> llmRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                recoveryModel.endpointName(),
                recoveryModel.endpointBaseUrl(),
                recoveryModel.modelName(),
                messages,
                request.getToolSpecifications(),
                Map.of(
                        "stage", "workflow_todo_recovery",
                        "error_category", failureCategory.name(),
                        "using_static_fix_model", recoveryModel.staticFixModel()
                )
        );
        String recoveryTraceId = observabilityService.recordLlmCall(
                runId,
                AgentObservabilityService.PHASE_SUMMARIZING,
                response.tokenUsage(),
                llmDurationMs,
                llmStartedAt,
                llmCompletedAt,
                recoveryModel.endpointName(),
                recoveryModel.modelName(),
                null,
                llmRequestSnapshot,
                text
        );

        eventService.append(runId, userId, "TODO_RECOVERY_COMPLETED", Map.of(
                "todo_id", nvl(item.getId()),
                "response_preview", preview(text),
                "error_category", failureCategory.name(),
                "recovery_model", recoveryModel.modelName(),
                "recovery_endpoint", recoveryModel.endpointName(),
                "using_static_fix_model", recoveryModel.staticFixModel()
        ));

        String json = extractJsonFromResponse(text);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            if (Boolean.TRUE.equals(parsed.get("abandon"))) {
                return null;
            }
            item.setDecisionLlmTraceId(recoveryTraceId);
            item.setDecisionStage("workflow_todo_recovery");
            item.setDecisionExcerpt(preview(text));
            return parsed;
        } catch (Exception e) {
            log.warn("Failed to parse recovery response: {}", e.getMessage());
            return null;
        }
    }

    private String extractJsonFromResponse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return null;
    }

    private RecoveryModelSelection resolveRecoveryModel(WorkflowRequest request,
                                                        TodoExecutionRecord failedRecord,
                                                        WorkflowConfig config) {
        TodoFailureCategory category = resolveFailureCategory(failedRecord);
        if (category == TodoFailureCategory.STATIC && !isBlank(config.staticFixModel())) {
            try {
                var resolved = aiServiceFactory.resolveLlm(config.staticFixEndpoint(), config.staticFixModel());
                ChatLanguageModel model = aiServiceFactory.buildChatModelWithProviderOrderAndTemperature(
                        resolved,
                        List.of(),
                        config.staticFixTemperature()
                );
                return new RecoveryModelSelection(
                        model,
                        nvl(resolved.endpointName()),
                        nvl(resolved.baseUrl()),
                        nvl(resolved.modelName()),
                        true
                );
            } catch (Exception e) {
                log.warn("Failed to init static fix model endpoint={}, model={}, fallback to run model, err={}",
                        config.staticFixEndpoint(), config.staticFixModel(), e.getMessage());
            }
        }
        return new RecoveryModelSelection(
                request.getModel(),
                nvl(request.getEndpointName()),
                nvl(request.getEndpointBaseUrl()),
                nvl(request.getModelName()),
                false
        );
    }

    private TodoFailureCategory resolveFailureCategory(TodoExecutionRecord failedRecord) {
        if (failedRecord == null) {
            return TodoFailureCategory.OTHER;
        }
        String raw = nvl(failedRecord.getFailureCategory()).trim().toUpperCase();
        if (raw.isBlank()) {
            return TodoFailureCategory.RUNTIME;
        }
        try {
            return TodoFailureCategory.valueOf(raw);
        } catch (Exception e) {
            return TodoFailureCategory.RUNTIME;
        }
    }

    private boolean canRetryByCategory(TodoFailureCategory category,
                                       int staticRecoveryAttempts,
                                       int runtimeRecoveryAttempts,
                                       int semanticRecoveryAttempts,
                                       int totalRecoveryAttempts,
                                       WorkflowConfig config) {
        if (totalRecoveryAttempts >= config.maxTotalRecoveryRetries()) {
            return false;
        }
        return switch (category) {
            case STATIC -> staticRecoveryAttempts < config.maxStaticRecoveryRetries();
            case SEMANTIC -> semanticRecoveryAttempts < config.maxSemanticRecoveryRetries();
            case RUNTIME, OTHER -> runtimeRecoveryAttempts < config.maxRuntimeRecoveryRetries();
        };
    }

    private TodoExecutionRecord executeItem(WorkflowRequest request,
                                            TodoItem item,
                                            Map<String, TodoExecutionRecord> context,
                                            WorkflowConfig config) {
        String runId = request.getRun().getId();
        String userId = request.getUserId();
        ExecutionMode mode = resolveExecutionMode(item, config.defaultExecutionMode());
        TodoType type = item.getType() == null ? TodoType.TOOL_CALL : item.getType();

        if (type == TodoType.THOUGHT) {
            return TodoExecutionRecord.builder()
                    .success(true)
                    .output(nvl(item.getReasoning()))
                    .summary(nvl(item.getReasoning()))
                    .toolCallsUsed(0)
                    .build();
        }

        if (type == TodoType.SUB_AGENT || mode == ExecutionMode.FORCE_SUB_AGENT) {
            if (!config.subAgentEnabled()) {
                return TodoExecutionRecord.builder()
                        .success(false)
                        .output("")
                        .summary("sub_agent_disabled")
                        .toolCallsUsed(0)
                        .build();
            }
            eventService.append(runId, userId, "SUB_AGENT_STARTED", Map.of(
                    "todo_id", nvl(item.getId()),
                    "goal", nvl(item.getReasoning())
            ));
            Map<String, Object> resolvedParams = paramResolver.resolve(item.getParams(), context);
            String goal = nvl(item.getReasoning()).isBlank()
                    ? "请完成任务: " + nvl(item.getId())
                    : nvl(item.getReasoning());
            Set<String> whitelist = request.getToolSpecifications().stream()
                    .map(ToolSpecification::name)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            SubAgentRunner.SubAgentResult subResult;
            AgentExecutionContextSnapshot snapshot = snapshotAgentContext();
            AgentContext.setPhase(AgentObservabilityService.PHASE_SUB_AGENT);
            AgentContext.setStage("workflow_sub_agent");
            AgentContext.setTodoContext(nvl(item.getId()), item.getSequence());
            AgentContext.setDecisionContext(nvl(item.getDecisionLlmTraceId()), nvl(item.getDecisionStage()), nvl(item.getDecisionExcerpt()));
            try {
                subResult = subAgentRunner.run(
                        SubAgentRunner.SubAgentRequest.builder()
                                .runId(runId)
                                .userId(userId)
                                .taskId(item.getId())
                                .goal(goal)
                                .context(buildSubAgentContext(request.getUserGoal(), context, resolvedParams))
                                .seedArgs(resolvedParams)
                                .toolWhitelist(whitelist)
                                .toolSpecifications(request.getToolSpecifications())
                                .maxSteps(Math.min(config.maxToolCallsPerSubAgent(), config.subAgentMaxSteps()))
                                .endpointName(request.getEndpointName())
                                .endpointBaseUrl(request.getEndpointBaseUrl())
                                .modelName(request.getModelName())
                                .build(),
                        request.getModel()
                );
            } finally {
                restoreAgentContext(snapshot);
            }

            int usedCalls = subResult.getSteps() == null ? 1 : Math.max(1, subResult.getSteps().size());
            toolCallCounter.increment(runId, usedCalls);

            eventService.append(runId, userId, "SUB_AGENT_FINISHED", Map.of(
                    "todo_id", nvl(item.getId()),
                    "success", subResult.isSuccess(),
                    "tool_calls_used", usedCalls,
                    "summary", preview(subResult.getAnswer())
            ));

            if (!subResult.isSuccess()) {
                return TodoExecutionRecord.builder()
                        .success(false)
                        .output(nvl(subResult.getError()))
                        .summary(nvl(subResult.getError()))
                        .toolCallsUsed(usedCalls)
                        .build();
            }
            return TodoExecutionRecord.builder()
                    .success(true)
                    .output(nvl(subResult.getAnswer()))
                    .summary(preview(subResult.getAnswer()))
                    .toolCallsUsed(usedCalls)
                    .build();
        }

        if (toolCallCounter.isLimitReached(runId, config.maxToolCalls())) {
            eventService.append(runId, userId, "TOOL_CALL_LIMIT_REACHED", Map.of(
                    "limit", config.maxToolCalls(),
                    "used", toolCallCounter.get(runId)
            ));
            return TodoExecutionRecord.builder()
                    .success(false)
                    .output("")
                    .summary("tool_call_limit_reached")
                    .toolCallsUsed(0)
                    .build();
        }

        Map<String, Object> resolvedParams = paramResolver.resolve(item.getParams(), context);
        String toolName = nvl(item.getToolName());
        List<Map<String, String>> unresolvedPlaceholders = collectUnresolvedPlaceholderRefs(resolvedParams);
        if (!unresolvedPlaceholders.isEmpty()) {
            Map<String, String> first = unresolvedPlaceholders.get(0);
            String errorMessage = "PARAM_PLACEHOLDER_UNRESOLVED: tool=" + toolName
                    + ", param=" + nvl(first.get("paramKey"))
                    + ", placeholder=" + nvl(first.get("rawPlaceholder"));
            eventService.append(runId, userId, "TOOL_CALL_PLACEHOLDER_UNRESOLVED", Map.of(
                    "todo_id", nvl(item.getId()),
                    "tool_name", toolName,
                    "toolName", toolName,
                    "error_category", "PARAM_PLACEHOLDER_UNRESOLVED",
                    "unresolved_placeholders", unresolvedPlaceholders
            ));
            return TodoExecutionRecord.builder()
                    .success(false)
                    .output("")
                    .summary(errorMessage)
                    .toolCallsUsed(0)
                    .build();
        }
        String displayName = toolDisplayName(toolName);
        String description = toolDescription(toolName);
        eventService.append(runId, userId, "TOOL_CALL_STARTED", Map.of(
                "todo_id", nvl(item.getId()),
                "tool_name", toolName,
                "toolName", toolName,
                "displayName", displayName,
                "description", description,
                "parameters", resolvedParams
        ));

        if ("executePython".equals(toolName) && config.staticPrecheckEnabled()) {
            AgentExecutionContextSnapshot snapshot = snapshotAgentContext();
            AgentContext.setPhase(AgentObservabilityService.PHASE_TOOL_EXECUTION);
            AgentContext.setStage("workflow_static_precheck");
            AgentContext.setTodoContext(nvl(item.getId()), item.getSequence());
            AgentContext.setDecisionContext(nvl(item.getDecisionLlmTraceId()), nvl(item.getDecisionStage()), nvl(item.getDecisionExcerpt()));
            PythonStaticPrecheckService.Result precheck;
            try {
                precheck = pythonStaticPrecheckService.check(
                        firstNonBlank(resolvedParams.get("code"), resolvedParams.get("arg0")),
                        firstNonBlank(resolvedParams.get("dataset_ids"), resolvedParams.get("dataset_id"), resolvedParams.get("datasetId"), resolvedParams.get("arg1")),
                        resolvedParams
                );
            } finally {
                restoreAgentContext(snapshot);
            }
            if (!precheck.isPassed()) {
                String summary = nvl(precheck.getErrorCode()) + ": " + nvl(precheck.getMessage());
                eventService.append(runId, userId, "TOOL_CALL_STATIC_PRECHECK_FAILED", Map.of(
                        "todo_id", nvl(item.getId()),
                        "tool_name", toolName,
                        "error_category", TodoFailureCategory.STATIC.name(),
                        "summary", summary,
                        "report", precheck.getReport() == null ? Map.of() : precheck.getReport()
                ));
                return TodoExecutionRecord.builder()
                        .success(false)
                        .output("")
                        .summary(summary)
                        .toolCallsUsed(0)
                        .failureCategory(TodoFailureCategory.STATIC.name())
                        .precheckReport(precheck.getReport())
                        .build();
            }
        }

        ToolRouter.ToolInvocationResult invokeResult;
        AgentExecutionContextSnapshot snapshot = snapshotAgentContext();
        AgentContext.setPhase(AgentObservabilityService.PHASE_TOOL_EXECUTION);
        AgentContext.setStage("workflow_tool_execution");
        AgentContext.setTodoContext(nvl(item.getId()), item.getSequence());
        AgentContext.setDecisionContext(nvl(item.getDecisionLlmTraceId()), nvl(item.getDecisionStage()), nvl(item.getDecisionExcerpt()));
        try {
            invokeResult = toolRouter.invokeWithMeta(toolName, resolvedParams);
        } finally {
            restoreAgentContext(snapshot);
        }

        toolCallCounter.increment(runId, 1);
        boolean cacheHit = invokeResult.getCacheMeta() != null && invokeResult.getCacheMeta().isHit();
        int creditsConsumed = creditService.calculateToolCredits(toolName, cacheHit);

        eventService.append(runId, userId, "TOOL_CALL_FINISHED", Map.of(
                "todo_id", nvl(item.getId()),
                "tool_name", toolName,
                "toolName", toolName,
                "success", invokeResult.isSuccess(),
                "cacheHit", cacheHit,
                "creditsConsumed", creditsConsumed,
                "credits_consumed", creditsConsumed,
                "result_preview", preview(invokeResult.getOutput()),
                "cache", toolRouter.toEventCachePayload(invokeResult)
        ));

        if ("executePython".equals(toolName) && invokeResult.isSuccess() && config.semanticJudgeEnabled()) {
            Map<String, Object> toolOutput = parseJsonObject(nvl(invokeResult.getOutput()));
            PythonSemanticJudgeService.Result judgeResult = pythonSemanticJudgeService.judge(
                    PythonSemanticJudgeService.Request.builder()
                            .runId(runId)
                            .userGoal(request.getUserGoal())
                            .todoId(item.getId())
                            .toolName(toolName)
                            .todoReasoning(item.getReasoning())
                            .runArgs(resolvedParams)
                            .code(firstNonBlank(resolvedParams.get("code"), resolvedParams.get("arg0")))
                            .toolOutput(toolOutput)
                            .fallbackModel(request.getModel())
                            .fallbackEndpointName(request.getEndpointName())
                            .fallbackEndpointBaseUrl(request.getEndpointBaseUrl())
                            .fallbackModelName(request.getModelName())
                            .build()
            );
            boolean rejected = !judgeResult.isPass();
            observabilityService.recordSemanticJudgeCall(runId, rejected);
            if (rejected) {
                eventService.append(runId, userId, "SEMANTIC_JUDGE_REJECTED", Map.of(
                        "todo_id", nvl(item.getId()),
                        "category", nvl(judgeResult.getCategory()),
                        "severity", nvl(judgeResult.getSeverity()),
                        "reason_cn", nvl(judgeResult.getReasonCn())
                ));
                return TodoExecutionRecord.builder()
                        .success(false)
                        .output(nvl(invokeResult.getOutput()))
                        .summary("SEMANTIC_JUDGE_REJECTED: " + nvl(judgeResult.getCategory()) + ", " + nvl(judgeResult.getReasonCn()))
                        .toolCallsUsed(1)
                        .failureCategory(TodoFailureCategory.SEMANTIC.name())
                        .semanticJudgeReport(judgeResult.getReport())
                        .build();
            }
            return TodoExecutionRecord.builder()
                    .success(true)
                    .output(nvl(invokeResult.getOutput()))
                    .summary(preview(invokeResult.getOutput()))
                    .toolCallsUsed(1)
                    .semanticJudgeReport(judgeResult.getReport())
                    .build();
        }

        return TodoExecutionRecord.builder()
                .success(invokeResult.isSuccess())
                .output(nvl(invokeResult.getOutput()))
                .summary(preview(invokeResult.getOutput()))
                .toolCallsUsed(1)
                .failureCategory(invokeResult.isSuccess() ? "" : TodoFailureCategory.RUNTIME.name())
                .build();
    }

    private String buildSubAgentContext(String userGoal,
                                        Map<String, TodoExecutionRecord> context,
                                        Map<String, Object> resolvedParams) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user_goal", nvl(userGoal));
        payload.put("resolved_params", resolvedParams == null ? Map.of() : resolvedParams);
        payload.put("done", context == null ? Map.of() : context);
        return safeWrite(payload);
    }

    private String generateFinalAnswer(WorkflowRequest request,
                                       List<TodoItem> completed,
                                       Map<String, TodoExecutionRecord> context) {
        String runId = request.getRun().getId();
        String userId = request.getUserId();
        eventService.append(runId, userId, "FINAL_ANSWER_GENERATING", Map.of(
                "completed_items", completed == null ? 0 : completed.size()
        ));

        List<Map<String, Object>> summary = new ArrayList<>();
        for (TodoItem item : completed == null ? List.<TodoItem>of() : completed) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", nvl(item.getId()));
            row.put("sequence", item.getSequence());
            row.put("type", item.getType() == null ? "" : item.getType().name());
            row.put("status", item.getStatus() == null ? "" : item.getStatus().name());
            row.put("summary", nvl(item.getResultSummary()));
            summary.add(row);
        }

        // 加载消息历史（多轮对话支持）
        String dialogueContext = buildDialogueContext(runId, request.getUserGoal());

        String userMessageContent;
        if (dialogueContext.isBlank()) {
            userMessageContent = "当前轮次用户需求: " + nvl(request.getUserGoal())
                    + "\n执行摘要: " + safeWrite(summary)
                    + "\n执行上下文: " + safeWrite(context);
        } else {
            userMessageContent = "历史对话压缩内容：\n" + dialogueContext
                    + "\n\n当前轮次用户需求: " + nvl(request.getUserGoal())
                    + "\n执行摘要: " + safeWrite(summary)
                    + "\n执行上下文: " + safeWrite(context)
                    + "\n\n请参考历史对话，以当前轮次用户需求为重点回答。";
        }

        List<ChatMessage> messages = List.of(
                new SystemMessage(promptService.workflowFinalSystemPrompt()),
                new UserMessage(userMessageContent)
        );

        // 设置当前 phase 并记录开始时间
        String previousStage = AgentContext.getStage();
        AgentContext.setPhase(AgentObservabilityService.PHASE_SUMMARIZING);
        AgentContext.setStage("workflow_final_answer");
        long llmStartedAt = System.currentTimeMillis();
        Response<AiMessage> response;
        try {
            response = request.getModel().generate(messages);
        } finally {
            if (previousStage == null || previousStage.isBlank()) {
                AgentContext.clearStage();
            } else {
                AgentContext.setStage(previousStage);
            }
        }
        long llmCompletedAt = System.currentTimeMillis();
        long llmDurationMs = llmCompletedAt - llmStartedAt;
        String answer = response.content() == null ? "" : nvl(response.content().text());

        Map<String, Object> llmRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                request.getEndpointName(),
                request.getEndpointBaseUrl(),
                request.getModelName(),
                messages,
                request.getToolSpecifications(),
                Map.of("stage", "workflow_final_answer")
        );
        observabilityService.recordLlmCall(
                runId,
                AgentObservabilityService.PHASE_SUMMARIZING,
                response.tokenUsage(),
                llmDurationMs,
                llmStartedAt,
                llmCompletedAt,
                request.getEndpointName(),
                request.getModelName(),
                null,
                llmRequestSnapshot,
                answer
        );

        eventService.append(runId, userId, "FINAL_ANSWER_COMPLETED", Map.of(
                "answer_preview", preview(answer),
                "answerPreview", preview(answer),
                "endpoint", nvl(request.getEndpointName()),
                "model", nvl(request.getModelName())
        ));
        return answer;
    }

    private WorkflowConfig resolveConfig() {
        AgentLlmProperties.Runtime runtime = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .orElse(Optional.ofNullable(llmProperties.getRuntime()).orElse(new AgentLlmProperties.Runtime()));
        AgentLlmProperties.Execution execution = runtime.getExecution();
        AgentLlmProperties.SubAgent subAgent = runtime.getSubAgent();
        AgentLlmProperties.Judge judge = runtime.getJudge();

        int maxToolCalls = clampInt(firstPositive(
                execution == null ? null : execution.getMaxToolCalls(),
                defaultMaxToolCalls
        ), 1, 200);
        int maxPerSubAgent = clampInt(firstPositive(
                execution == null ? null : execution.getMaxToolCallsPerSubAgent(),
                defaultMaxToolCallsPerSubAgent
        ), 1, 100);
        boolean failFast = execution != null && execution.getFailFast() != null
                ? execution.getFailFast()
                : defaultFailFast;
        ExecutionMode executionMode = parseMode(execution == null ? null : execution.getDefaultExecutionMode());
        boolean subAgentEnabled = subAgent != null && subAgent.getEnabled() != null
                ? subAgent.getEnabled()
                : defaultSubAgentEnabled;
        int subAgentMaxSteps = clampInt(firstPositive(
                subAgent == null ? null : subAgent.getMaxSteps(),
                defaultSubAgentMaxSteps
        ), 1, 20);

        int maxRetriesPerTodo = clampInt(firstPositive(
                execution == null ? null : execution.getMaxRetriesPerTodo(),
                defaultMaxRetriesPerTodo
        ), 1, 10);
        int defaultTotalRecoveryRetries = Math.max(0, maxRetriesPerTodo - 1);
        int maxTotalRecoveryRetries = clampInt(firstNonNegative(
                execution == null ? null : execution.getMaxTotalRecoveryRetries(),
                defaultTotalRecoveryRetries
        ), 0, 20);
        int maxStaticRecoveryRetries = clampInt(firstNonNegative(
                execution == null ? null : execution.getMaxStaticRecoveryRetries(),
                maxTotalRecoveryRetries
        ), 0, 20);
        int maxRuntimeRecoveryRetries = clampInt(firstNonNegative(
                execution == null ? null : execution.getMaxRuntimeRecoveryRetries(),
                maxTotalRecoveryRetries
        ), 0, 20);
        int maxSemanticRecoveryRetries = clampInt(firstNonNegative(
                execution == null ? null : execution.getMaxSemanticRecoveryRetries(),
                maxTotalRecoveryRetries
        ), 0, 20);
        boolean staticPrecheckEnabled = execution == null || execution.getStaticPrecheckEnabled() == null
                || execution.getStaticPrecheckEnabled();
        boolean semanticJudgeEnabled = judge != null && judge.getSemanticEnabled() != null && judge.getSemanticEnabled();
        String staticFixEndpoint = execution == null ? "" : nvl(execution.getStaticFixEndpoint()).trim();
        String staticFixModel = execution == null ? "" : nvl(execution.getStaticFixModel()).trim();
        Double staticFixTemperature = execution == null ? null : execution.getStaticFixTemperature();

        return new WorkflowConfig(
                maxToolCalls,
                maxPerSubAgent,
                maxRetriesPerTodo,
                failFast,
                executionMode,
                subAgentEnabled,
                subAgentMaxSteps,
                staticPrecheckEnabled,
                semanticJudgeEnabled,
                maxStaticRecoveryRetries,
                maxRuntimeRecoveryRetries,
                maxSemanticRecoveryRetries,
                maxTotalRecoveryRetries,
                staticFixEndpoint,
                staticFixModel,
                staticFixTemperature
        );
    }

    private int firstPositive(Integer value, int fallback) {
        if (value != null && value > 0) {
            return value;
        }
        return fallback;
    }

    private int firstNonNegative(Integer value, int fallback) {
        if (value != null && value >= 0) {
            return value;
        }
        return fallback;
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private ExecutionMode parseMode(String text) {
        String candidate = nvl(text).trim();
        if (candidate.isBlank()) {
            candidate = nvl(defaultExecutionMode).trim();
        }
        try {
            return ExecutionMode.valueOf(candidate.toUpperCase());
        } catch (Exception e) {
            return ExecutionMode.AUTO;
        }
    }

    private ExecutionMode resolveExecutionMode(TodoItem item, ExecutionMode defaultMode) {
        if (item.getExecutionMode() != null) {
            return item.getExecutionMode();
        }
        return defaultMode;
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > 500) {
            return text.substring(0, 500);
        }
        return text;
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

            // 记录上下文压缩事件（如果发生了压缩）
            AgentContextCompressor.CompressionResult compression = result.compression();
            if (compression != null && compression.compressedMessages() < compression.originalMessages()) {
                eventService.append(runId, null, "CONTEXT_COMPRESSED", Map.of(
                        "strategy", nvl(compression.strategy()),
                        "original_count", compression.originalMessages(),
                        "compressed_count", compression.compressedMessages(),
                        "dropped_sequences", compression.droppedSequences()
                ));
            }

            return result.text();
        } catch (Exception e) {
            log.warn("Failed to build dialogue context for runId={}, ignoring: {}", runId, e.getMessage());
            return "";
        }
    }

    private String toolDisplayName(String toolName) {
        return switch (nvl(toolName)) {
            case "searchStock" -> "搜索股票代码";
            case "searchFund" -> "搜索基金代码";
            case "searchIndex" -> "搜索指数代码";
            case "getStockInfo" -> "查询股票基础信息";
            case "getStockDaily" -> "获取股票行情数据";
            case "getIndexInfo" -> "查询指数基础信息";
            case "getIndexDaily" -> "获取指数行情数据";
            case "executePython" -> "执行编程计算";
            default -> "调用工具";
        };
    }

    private String toolDescription(String toolName) {
        return switch (nvl(toolName)) {
            case "searchStock", "searchFund", "searchIndex" -> "根据关键词检索代码";
            case "getStockInfo", "getIndexInfo" -> "读取资产基础信息";
            case "getStockDaily", "getIndexDaily" -> "读取区间行情数据";
            case "executePython" -> "在沙箱中执行 Python 计算";
            default -> "执行工具调用";
        };
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(text, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return normalized;
            }
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private AgentExecutionContextSnapshot snapshotAgentContext() {
        return new AgentExecutionContextSnapshot(
                AgentContext.getPhase(),
                AgentContext.getStage(),
                AgentContext.getTodoId(),
                AgentContext.getTodoSequence(),
                AgentContext.getSubAgentStepIndex(),
                AgentContext.getPythonRefineAttempt(),
                AgentContext.getDecisionTraceId(),
                AgentContext.getDecisionStage(),
                AgentContext.getDecisionExcerpt()
        );
    }

    private void restoreAgentContext(AgentExecutionContextSnapshot snapshot) {
        if (snapshot == null) {
            AgentContext.clearPhase();
            AgentContext.clearStage();
            AgentContext.clearTodoContext();
            AgentContext.clearSubAgentStepIndex();
            AgentContext.clearPythonRefineAttempt();
            AgentContext.clearDecisionContext();
            return;
        }
        if (snapshot.phase() == null || snapshot.phase().isBlank()) {
            AgentContext.clearPhase();
        } else {
            AgentContext.setPhase(snapshot.phase());
        }
        if (snapshot.stage() == null || snapshot.stage().isBlank()) {
            AgentContext.clearStage();
        } else {
            AgentContext.setStage(snapshot.stage());
        }
        if (snapshot.todoId() == null || snapshot.todoId().isBlank()) {
            AgentContext.clearTodoContext();
        } else {
            AgentContext.setTodoContext(snapshot.todoId(), snapshot.todoSequence());
        }
        AgentContext.setSubAgentStepIndex(snapshot.subAgentStepIndex());
        AgentContext.setPythonRefineAttempt(snapshot.pythonRefineAttempt());
        if (snapshot.decisionTraceId() == null || snapshot.decisionTraceId().isBlank()) {
            AgentContext.clearDecisionContext();
        } else {
            AgentContext.setDecisionContext(snapshot.decisionTraceId(), snapshot.decisionStage(), snapshot.decisionExcerpt());
        }
    }

    private String safeWrite(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private List<Map<String, String>> collectUnresolvedPlaceholderRefs(Object value) {
        List<Map<String, String>> refs = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();
        collectUnresolvedPlaceholderRefs(value, "", refs, dedup);
        return refs;
    }

    @SuppressWarnings("unchecked")
    private void collectUnresolvedPlaceholderRefs(Object value,
                                                  String path,
                                                  List<Map<String, String>> refs,
                                                  Set<String> dedup) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String nextPath = path.isBlank() ? key : path + "." + key;
                collectUnresolvedPlaceholderRefs(entry.getValue(), nextPath, refs, dedup);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (int idx = 0; idx < list.size(); idx++) {
                String nextPath = path + "[" + idx + "]";
                collectUnresolvedPlaceholderRefs(list.get(idx), nextPath, refs, dedup);
            }
            return;
        }
        if (!(value instanceof String text) || !text.contains("${")) {
            return;
        }
        Matcher matcher = UNRESOLVED_PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group();
            String paramKey = path.isBlank() ? "<root>" : path;
            String dedupKey = paramKey + "|" + raw;
            if (!dedup.add(dedupKey)) {
                continue;
            }
            refs.add(Map.of(
                    "paramKey", paramKey,
                    "rawPlaceholder", raw
            ));
        }
    }

    private record WorkflowConfig(int maxToolCalls,
                                  int maxToolCallsPerSubAgent,
                                  int maxRetriesPerTodo,
                                  boolean failFast,
                                  ExecutionMode defaultExecutionMode,
                                  boolean subAgentEnabled,
                                  int subAgentMaxSteps,
                                  boolean staticPrecheckEnabled,
                                  boolean semanticJudgeEnabled,
                                  int maxStaticRecoveryRetries,
                                  int maxRuntimeRecoveryRetries,
                                  int maxSemanticRecoveryRetries,
                                  int maxTotalRecoveryRetries,
                                  String staticFixEndpoint,
                                  String staticFixModel,
                                  Double staticFixTemperature) {
    }

    private record RecoveryModelSelection(ChatLanguageModel model,
                                          String endpointName,
                                          String endpointBaseUrl,
                                          String modelName,
                                          boolean staticFixModel) {
    }

    private record AgentExecutionContextSnapshot(String phase,
                                                 String stage,
                                                 String todoId,
                                                 Integer todoSequence,
                                                 Integer subAgentStepIndex,
                                                 Integer pythonRefineAttempt,
                                                 String decisionTraceId,
                                                 String decisionStage,
                                                 String decisionExcerpt) {
    }

    @Data
    @Builder
    public static class WorkflowRequest {
        private AgentRun run;
        private String userId;
        private String userGoal;
        private TodoPlan todoPlan;
        private ChatLanguageModel model;
        private List<ToolSpecification> toolSpecifications;
        private String endpointName;
        private String endpointBaseUrl;
        private String modelName;
    }
}

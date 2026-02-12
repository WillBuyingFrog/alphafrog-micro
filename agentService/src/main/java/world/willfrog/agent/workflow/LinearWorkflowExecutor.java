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
import world.willfrog.agent.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.service.AgentLlmRequestSnapshotBuilder;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentRunStateStore;
import world.willfrog.agent.tool.ToolRouter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class LinearWorkflowExecutor implements WorkflowExecutor {

    private final AgentEventService eventService;
    private final AgentPromptService promptService;
    private final ToolRouter toolRouter;
    private final SubAgentRunner subAgentRunner;
    private final TodoParamResolver paramResolver;
    private final ToolCallCounter toolCallCounter;
    private final AgentRunStateStore stateStore;
    private final AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    private final AgentObservabilityService observabilityService;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final AgentLlmProperties llmProperties;
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
                        .completedItems(completed)
                        .context(context)
                        .toolCallsUsed(toolCallCounter.get(runId))
                        .build();
            }

            TodoItem item = items.get(idx);
            item.setStatus(TodoStatus.RUNNING);
            eventService.append(runId, userId, "TODO_STARTED", Map.of(
                    "todo_id", nvl(item.getId()),
                    "sequence", item.getSequence(),
                    "type", item.getType() == null ? "" : item.getType().name(),
                    "tool", nvl(item.getToolName())
            ));

            TodoExecutionRecord record = executeItem(request, item, context, config);
            item.setCompletedAt(Instant.now());
            item.setResultSummary(nvl(record.getSummary()));
            item.setOutput(nvl(record.getOutput()));

            if (record.isSuccess()) {
                item.setStatus(TodoStatus.COMPLETED);
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

            completed.add(item);
            context.put(item.getId(), record);
            WorkflowState checkpoint = WorkflowState.builder()
                    .currentIndex(idx + 1)
                    .completedItems(completed)
                    .context(context)
                    .toolCallsUsed(toolCallCounter.get(runId))
                    .savedAt(Instant.now())
                    .build();
            stateStore.saveWorkflowState(runId, checkpoint);

            if (!record.isSuccess() && config.failFast()) {
                String finalAnswer = generateFinalAnswer(request, completed, context);
                return WorkflowExecutionResult.builder()
                        .paused(false)
                        .success(false)
                        .failureReason("todo_failed:" + nvl(item.getId()))
                        .finalAnswer(finalAnswer)
                        .completedItems(completed)
                        .context(context)
                        .toolCallsUsed(toolCallCounter.get(runId))
                        .build();
            }
        }

        stateStore.clearWorkflowState(runId);
        String finalAnswer = generateFinalAnswer(request, completed, context);
        if (hasFailure) {
            return WorkflowExecutionResult.builder()
                    .paused(false)
                    .success(false)
                    .failureReason("todo_partial_failed")
                    .finalAnswer(finalAnswer)
                    .completedItems(completed)
                    .context(context)
                    .toolCallsUsed(toolCallCounter.get(runId))
                    .build();
        }
        return WorkflowExecutionResult.builder()
                .paused(false)
                .success(true)
                .failureReason("")
                .finalAnswer(finalAnswer)
                .completedItems(completed)
                .context(context)
                .toolCallsUsed(toolCallCounter.get(runId))
                .build();
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
            AgentContext.setPhase(AgentObservabilityService.PHASE_SUB_AGENT);
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
                AgentContext.clearPhase();
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
        eventService.append(runId, userId, "TOOL_CALL_STARTED", Map.of(
                "todo_id", nvl(item.getId()),
                "tool_name", nvl(item.getToolName()),
                "parameters", resolvedParams
        ));

        ToolRouter.ToolInvocationResult invokeResult;
        AgentContext.setPhase(AgentObservabilityService.PHASE_TOOL_EXECUTION);
        try {
            invokeResult = toolRouter.invokeWithMeta(item.getToolName(), resolvedParams);
        } finally {
            AgentContext.clearPhase();
        }

        toolCallCounter.increment(runId, 1);

        eventService.append(runId, userId, "TOOL_CALL_FINISHED", Map.of(
                "todo_id", nvl(item.getId()),
                "tool_name", nvl(item.getToolName()),
                "success", invokeResult.isSuccess(),
                "result_preview", preview(invokeResult.getOutput()),
                "cache", toolRouter.toEventCachePayload(invokeResult)
        ));

        return TodoExecutionRecord.builder()
                .success(invokeResult.isSuccess())
                .output(nvl(invokeResult.getOutput()))
                .summary(preview(invokeResult.getOutput()))
                .toolCallsUsed(1)
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

        List<ChatMessage> messages = List.of(
                new SystemMessage(promptService.workflowFinalSystemPrompt()),
                new UserMessage("用户目标: " + nvl(request.getUserGoal()) + "\n执行摘要: " + safeWrite(summary) + "\n执行上下文: " + safeWrite(context))
        );

        long llmStartedAt = System.currentTimeMillis();
        Response<AiMessage> response = request.getModel().generate(messages);
        long llmDurationMs = System.currentTimeMillis() - llmStartedAt;
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
                request.getEndpointName(),
                request.getModelName(),
                null,
                llmRequestSnapshot,
                answer
        );

        eventService.append(runId, userId, "FINAL_ANSWER_COMPLETED", Map.of(
                "answer_preview", preview(answer)
        ));
        return answer;
    }

    private WorkflowConfig resolveConfig() {
        AgentLlmProperties.Runtime runtime = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .orElse(Optional.ofNullable(llmProperties.getRuntime()).orElse(new AgentLlmProperties.Runtime()));
        AgentLlmProperties.Execution execution = runtime.getExecution();
        AgentLlmProperties.SubAgent subAgent = runtime.getSubAgent();

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

        return new WorkflowConfig(maxToolCalls, maxPerSubAgent, failFast, executionMode, subAgentEnabled, subAgentMaxSteps);
    }

    private int firstPositive(Integer value, int fallback) {
        if (value != null && value > 0) {
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

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private String safeWrite(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private record WorkflowConfig(int maxToolCalls,
                                  int maxToolCallsPerSubAgent,
                                  boolean failFast,
                                  ExecutionMode defaultExecutionMode,
                                  boolean subAgentEnabled,
                                  int subAgentMaxSteps) {
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

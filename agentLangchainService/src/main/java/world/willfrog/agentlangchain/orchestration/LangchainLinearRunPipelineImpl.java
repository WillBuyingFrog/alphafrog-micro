package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agentlangchain.orchestration.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainPlanningRequest;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agentlangchain.failure.LangchainFailureDecision;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.tools.LangchainToolInvocationKeys;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Service
@Slf4j
public class LangchainLinearRunPipelineImpl implements LangchainLinearRunPipeline {

    private final LangchainAiPlanner planner;
    private final LangchainLinearWorkflowExecutor linearWorkflowExecutor;
    private final LangchainDagWorkflowExecutor dagWorkflowExecutor;
    private final LangchainRunStageModelResolver stageModelResolver;
    private final AgentRunMapper runMapper;
    private final AgentEventService eventService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ToolProvider> toolProviderProvider;
    private final ObjectProvider<AgentRunStateStore> stateStoreProvider;
    private final ObjectProvider<AgentObservabilityService> observabilityServiceProvider;
    private final LangchainFailureMapper failureMapper;
    private final LangchainFollowUpContextSupport followUpContextSupport;
    private final AgentMessageService messageService;
    private final LangchainRunExecutionGuard executionGuard;
    private final Executor langchainRunTaskExecutor;

    public LangchainLinearRunPipelineImpl(LangchainAiPlanner planner,
                                          LangchainLinearWorkflowExecutor linearWorkflowExecutor,
                                          LangchainDagWorkflowExecutor dagWorkflowExecutor,
                                          LangchainRunStageModelResolver stageModelResolver,
                                          AgentRunMapper runMapper,
                                          AgentEventService eventService,
                                          ObjectMapper objectMapper,
                                          ObjectProvider<ToolProvider> toolProviderProvider,
                                          ObjectProvider<AgentRunStateStore> stateStoreProvider,
                                          ObjectProvider<AgentObservabilityService> observabilityServiceProvider,
                                          LangchainFailureMapper failureMapper,
                                          LangchainFollowUpContextSupport followUpContextSupport,
                                          AgentMessageService messageService,
                                          LangchainRunExecutionGuard executionGuard,
                                          @Qualifier("agentLangchainRunTaskExecutor") Executor langchainRunTaskExecutor) {
        this.planner = planner;
        this.linearWorkflowExecutor = linearWorkflowExecutor;
        this.dagWorkflowExecutor = dagWorkflowExecutor;
        this.stageModelResolver = stageModelResolver;
        this.runMapper = runMapper;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.toolProviderProvider = toolProviderProvider;
        this.stateStoreProvider = stateStoreProvider;
        this.observabilityServiceProvider = observabilityServiceProvider;
        this.failureMapper = failureMapper;
        this.followUpContextSupport = followUpContextSupport;
        this.messageService = messageService;
        this.executionGuard = executionGuard;
        this.langchainRunTaskExecutor = langchainRunTaskExecutor;
    }

    @Override
    public void launchAsync(AgentRun run) {
        langchainRunTaskExecutor.execute(() -> executeRun(run));
    }

    void executeRun(AgentRun initialRun) {
        if (initialRun == null || isBlank(initialRun.getId())) {
            return;
        }
        AgentRun run = runMapper.findById(initialRun.getId());
        if (run == null) {
            log.warn("LangChain run not found, skip: {}", initialRun.getId());
            return;
        }
        String runId = run.getId();
        String userId = run.getUserId();
        String userGoal = "";
        try {
            AgentContext.setRunId(runId);
            AgentContext.setUserId(userId);
            if (!eventService.isRunnable(runId, userId)) {
                return;
            }
            runMapper.updateStatus(runId, userId, AgentRunStatus.EXECUTING);
            markRunStatus(runId, AgentRunStatus.EXECUTING);
            eventService.append(runId, userId, "EXECUTION_STARTED", Map.of(
                    "run_id", runId,
                    "engine", "agentLangchainService",
                    "workflow", "pending_plan"
            ));

            LangchainRunStageModelResolver.StageModels stageModels = stageModelResolver.resolve(run);
            boolean captureLlmRequests = eventService.extractCaptureLlmRequests(run.getExt());
            AgentObservabilityService observabilityService = observabilityServiceProvider.getIfAvailable();
            if (observabilityService != null) {
                observabilityService.initializeRun(
                        runId,
                        firstNonBlank(stageModels.planningEndpointName(), eventService.extractEndpointName(run.getExt())),
                        firstNonBlank(stageModels.planningModelName(), eventService.extractModelName(run.getExt())),
                        captureLlmRequests);
            }
            LangchainFollowUpContextSupport.ExecutionContext executionContext = followUpContextSupport.resolve(run);
            userGoal = executionContext.userGoal();
            String dialogueContext = executionContext.dialogueContext();
            AgentEventService.RunConfig runConfig = eventService.extractRunConfig(run.getExt());
            AgentContext.setWebSearchEnabled(runConfig.webSearchEnabled());
            AgentContext.setWebSearchConfig(runConfig.webSearchConfig());

            List<ToolSpecification> toolSpecifications = resolveToolSpecifications(runConfig, userGoal);
            LangchainLinearWorkflowRequest workflowRequest = LangchainLinearWorkflowRequest.builder()
                    .runId(runId)
                    .userId(userId)
                    .userGoal(userGoal)
                    .dialogueContext(dialogueContext)
                    .model(stageModels.executionModel())
                    .planningModel(stageModels.planningModel())
                    .executionModel(stageModels.executionModel())
                    .finalAnswerModel(stageModels.finalAnswerModel())
                    .planningEndpointName(stageModels.planningEndpointName())
                    .planningModelName(stageModels.planningModelName())
                    .planningProviderOrder(stageModels.planningProviderOrder())
                    .toolSpecifications(toolSpecifications)
                    .webSearchEnabled(runConfig.webSearchEnabled())
                    .codeInterpreterEnabled(runConfig.codeInterpreterEnabled())
                    .build();

            AgentContext.setPhase("planning");
            LangchainTodoPlan plan = planner.plan(LangchainPlanningRequest.builder()
                    .runId(runId)
                    .userId(userId)
                    .userGoal(userGoal)
                    .dialogueContext(dialogueContext)
                    .model(stageModels.planningModel())
                    .planningEndpointName(stageModels.planningEndpointName())
                    .planningModelName(stageModels.planningModelName())
                    .planningProviderOrder(stageModels.planningProviderOrder())
                    .toolSpecifications(toolSpecifications)
                    .executionMode(PlanExecutionMode.AUTO)
                    .build());

            boolean useDag = LangchainWorkflowRouting.shouldUseDag(plan);
            eventService.append(runId, userId, "PLAN_READY", Map.of(
                    "execution_mode", plan.getExecutionMode() == null ? "AUTO" : plan.getExecutionMode().name(),
                    "workflow", useDag ? "dag" : "linear",
                    "todo_count", plan.getItems() == null ? 0 : plan.getItems().size()
            ));

            if (abortIfStopped(runId, userId, "before_execution")) {
                return;
            }

            LangchainLinearWorkflowResult result = useDag
                    ? dagWorkflowExecutor.executePlanned(workflowRequest, plan)
                    : linearWorkflowExecutor.executePlanned(workflowRequest, plan);

            if (result.isInterrupted() || abortIfStopped(runId, userId, "before_persist")) {
                log.info("LangChain run {} stopped before persist (interrupted={}, reason={})",
                        runId, result.isInterrupted(), result.getFailureReason());
                return;
            }

            runMapper.updatePlanJson(runId, userId, writeJson(result.getPlan()));
            if (result.isSuccess()) {
                String snapshot = attachObservability(
                        runId, buildSnapshot(userGoal, result, AgentRunStatus.COMPLETED), AgentRunStatus.COMPLETED, null, null);
                runMapper.updateSnapshot(runId, userId, AgentRunStatus.COMPLETED, snapshot, true, null);
                markRunStatus(runId, AgentRunStatus.COMPLETED);
                eventService.append(runId, userId, "WORKFLOW_COMPLETED", Map.of(
                        "answer", result.getFinalAnswer(),
                        "toolCallsUsed", result.getToolCallsUsed(),
                        "engine", "agentLangchainService"
                ));
                persistAssistantMessage(runId, userId, stageModels, result.getFinalAnswer());
            } else {
                publishFailure(runId, userId, userGoal, result, null);
            }
        } catch (Exception e) {
            log.error("LangChain run failed: runId={}", runId, e);
            publishFailure(runId, userId, userGoal,
                    LangchainLinearWorkflowResult.builder()
                            .success(false)
                            .failureReason(e.getMessage())
                            .toolCallsUsed(0)
                            .build(),
                    e);
        } finally {
            AgentContext.clear();
        }
    }

    private List<ToolSpecification> resolveToolSpecifications(AgentEventService.RunConfig runConfig, String userGoal) {
        ToolProvider provider = toolProviderProvider.getIfAvailable();
        if (provider == null) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(LangchainToolInvocationKeys.WEB_SEARCH_ENABLED, runConfig.webSearchEnabled());
        params.put(LangchainToolInvocationKeys.CODE_INTERPRETER_ENABLED, runConfig.codeInterpreterEnabled());
        return provider.provideTools(ToolProviderRequest.builder()
                        .userMessage(UserMessage.from(nvl(userGoal)))
                        .invocationContext(InvocationContext.builder()
                                .userMessage(UserMessage.from(nvl(userGoal)))
                                .invocationParameters(InvocationParameters.from(params))
                                .timestampNow()
                                .build())
                        .build())
                .tools()
                .keySet()
                .stream()
                .toList();
    }

    private String buildSnapshot(String userGoal,
                                 LangchainLinearWorkflowResult result,
                                 AgentRunStatus status) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("user_goal", userGoal);
        snapshot.put("plan", result.getPlan());
        snapshot.put("completed_items", result.getCompletedTodos());
        snapshot.put("answer", nvl(result.getFinalAnswer()));
        snapshot.put("answer_markdown", nvl(result.getFinalAnswer()));
        snapshot.put("status", status.name());
        snapshot.put("failure_reason", nvl(result.getFailureReason()));
        snapshot.put("tool_calls_used", result.getToolCallsUsed());
        snapshot.put("engine", "agentLangchainService");
        return writeJson(snapshot);
    }

    private void persistAssistantMessage(String runId,
                                         String userId,
                                         LangchainRunStageModelResolver.StageModels stageModels,
                                         String finalAnswer) {
        if (isBlank(finalAnswer)) {
            return;
        }
        try {
            String assistantMetaJson = messageService.buildMetaJson(
                    stageModels.planningModelName(),
                    stageModels.planningEndpointName(),
                    null,
                    null);
            messageService.createAssistantMessage(runId, finalAnswer, assistantMetaJson);
            eventService.append(runId, userId, "MESSAGE_COMPLETED", Map.of(
                    "role", "assistant",
                    "content_preview", preview(finalAnswer, 200),
                    "model", nvl(stageModels.planningModelName()),
                    "endpoint", nvl(stageModels.planningEndpointName()),
                    "engine", "agentLangchainService"));
        } catch (Exception e) {
            log.warn("Failed to create assistant message for runId={}: {}", runId, e.getMessage());
        }
    }

    private String preview(String content, int maxLen) {
        if (content == null) {
            return "";
        }
        return content.length() <= maxLen ? content : content.substring(0, maxLen);
    }

    private void markRunStatus(String runId, AgentRunStatus status) {
        AgentRunStateStore stateStore = stateStoreProvider.getIfAvailable();
        if (stateStore != null) {
            stateStore.markRunStatus(runId, status.name());
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (!isBlank(primary)) {
            return primary;
        }
        return fallback == null ? "" : fallback;
    }

    private boolean abortIfStopped(String runId, String userId, String phase) {
        return executionGuard.stopReason(runId, userId)
                .map(reason -> {
                    log.info("LangChain run {} aborted at {} (control status={})", runId, phase, reason);
                    return true;
                })
                .orElse(false);
    }

    private void publishFailure(String runId,
                                String userId,
                                String userGoal,
                                LangchainLinearWorkflowResult result,
                                Throwable throwable) {
        if (abortIfStopped(runId, userId, "before_failure_persist")) {
            return;
        }
        String failureReason = nvl(result == null ? null : result.getFailureReason());
        LangchainFailureDecision decision = failureMapper.map(
                AgentContext.getPhase(),
                AgentContext.getTodoId(),
                null,
                failureReason,
                null,
                throwable,
                result == null ? null : result.getToolCallsUsed());
        String snapshot = attachObservability(
                runId,
                buildSnapshot(userGoal, result, AgentRunStatus.FAILED),
                AgentRunStatus.FAILED,
                decision.getObservabilityFailureType(),
                decision.getReason());
        runMapper.updateSnapshot(runId, userId, AgentRunStatus.FAILED, snapshot, true, decision.getReason());
        markRunStatus(runId, AgentRunStatus.FAILED);
        Map<String, Object> payload = new LinkedHashMap<>(decision.getEventPayload());
        payload.put("engine", "agentLangchainService");
        eventService.append(runId, userId, decision.getEventType(), payload);
    }

    private String attachObservability(String runId,
                                       String snapshot,
                                       AgentRunStatus status,
                                       String observabilityFailureType,
                                       String failureReason) {
        AgentObservabilityService observabilityService = observabilityServiceProvider.getIfAvailable();
        if (observabilityService == null) {
            return snapshot;
        }
        if (status == AgentRunStatus.FAILED && !isBlank(failureReason)) {
            observabilityService.recordFailure(
                    runId,
                    isBlank(observabilityFailureType) ? "WorkflowFailed" : observabilityFailureType,
                    failureReason);
        }
        return observabilityService.attachObservabilityToSnapshot(runId, snapshot, status);
    }
}

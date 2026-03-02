package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.entity.AgentRunMessage;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.agent.tool.MarketDataTools;
import world.willfrog.agent.tool.PythonSandboxTools;
import world.willfrog.agent.workflow.LinearWorkflowExecutor;
import world.willfrog.agent.workflow.TodoPlanner;
import world.willfrog.agent.workflow.WorkflowExecutionResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunExecutor {

    private final AgentRunMapper runMapper;
    private final AgentEventService eventService;
    private final AgentAiServiceFactory aiServiceFactory;
    private final MarketDataTools marketDataTools;
    private final PythonSandboxTools pythonSandboxTools;
    private final AgentRunStateStore stateStore;
    private final AgentObservabilityService observabilityService;
    private final AgentCreditService creditService;
    private final TodoPlanner todoPlanner;
    private final LinearWorkflowExecutor workflowExecutor;
    private final AgentMessageService messageService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private final AtomicInteger activeRuns = new AtomicInteger(0);
    private Timer runDurationTimer;

    @PostConstruct
    public void init() {
        Gauge.builder("run.active", activeRuns, AtomicInteger::get)
                .description("Currently active Agent Run count")
                .register(meterRegistry);
        this.runDurationTimer = Timer.builder("run.duration")
                .description("Agent Run execution duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @Async
    public void executeAsync(String runId) {
        try {
            execute(runId);
        } catch (Exception e) {
            log.error("Agent run execute failed: runId={}", runId, e);
        }
    }

    public void execute(String runId) {
        long startedAt = System.currentTimeMillis();
        activeRuns.incrementAndGet();
        try {
            doExecute(runId);
        } finally {
            activeRuns.decrementAndGet();
            runDurationTimer.record(System.currentTimeMillis() - startedAt, TimeUnit.MILLISECONDS);
        }
    }

    private void doExecute(String runId) {
        AgentRun run = runMapper.findById(runId);
        if (run == null) {
            log.warn("Agent run not found, ignore execute: {}", runId);
            return;
        }
        if (run.getStatus() == AgentRunStatus.CANCELED || run.getStatus() == AgentRunStatus.COMPLETED || run.getStatus() == AgentRunStatus.EXPIRED) {
            log.info("Agent run already terminated, skip execute: {} status={}", runId, run.getStatus());
            return;
        }

        String userId = run.getUserId();
        try {
            AgentContext.setRunId(runId);
            AgentContext.setUserId(userId);

            if (!eventService.isRunnable(runId, userId)) {
                return;
            }

            runMapper.updateStatus(runId, userId, AgentRunStatus.EXECUTING);
            eventService.append(runId, userId, "EXECUTION_STARTED", mapOf("run_id", runId));
            stateStore.markRunStatus(runId, AgentRunStatus.EXECUTING.name());

            String requestedEndpointName = eventService.extractEndpointName(run.getExt());
            String requestedModelName = eventService.extractModelName(run.getExt());
            AgentLlmResolver.ResolvedLlm resolvedLlm = aiServiceFactory.resolveLlm(requestedEndpointName, requestedModelName);
            String endpointName = resolvedLlm.endpointName();
            String modelName = resolvedLlm.modelName();
            String endpointBaseUrl = resolvedLlm.baseUrl();
            boolean captureLlmRequests = eventService.extractCaptureLlmRequests(run.getExt());
            boolean debugMode = eventService.extractDebugMode(run.getExt());
            AgentContext.setDebugMode(debugMode);
            var providerOrder = eventService.extractOpenRouterProviderOrder(run.getExt());

            observabilityService.initializeRun(runId, endpointName, modelName, captureLlmRequests);
            ChatLanguageModel chatModel = aiServiceFactory.buildChatModelWithProviderOrder(resolvedLlm, providerOrder);
            String userGoal = resolveUserGoal(run);
            AgentEventService.RunConfig runConfig = eventService.extractRunConfig(run.getExt());

            eventService.append(runId, userId, "RUN_CONFIG_APPLIED", mapOf(
                    "webSearchEnabled", runConfig.webSearchEnabled(),
                    "codeInterpreterEnabled", runConfig.codeInterpreterEnabled(),
                    "codeInterpreterMaxCredits", runConfig.codeInterpreterMaxCredits(),
                    "smartRetrievalEnabled", runConfig.smartRetrievalEnabled()
            ));
            if (runConfig.webSearchEnabled()) {
                eventService.append(runId, userId, "RUN_CAPABILITY_PLACEHOLDER", mapOf(
                        "capability", "webSearch",
                        "requested", true,
                        "available", false,
                        "reason", "backend_tool_not_implemented_yet"
                ));
            }
            if (runConfig.smartRetrievalEnabled()) {
                eventService.append(runId, userId, "RUN_CAPABILITY_PLACEHOLDER", mapOf(
                        "capability", "smartRetrieval",
                        "requested", true,
                        "available", false,
                        "reason", "backend_tool_not_implemented_yet"
                ));
            }

            List<ToolSpecification> toolSpecifications = new ArrayList<>();
            toolSpecifications.addAll(ToolSpecifications.toolSpecificationsFrom(marketDataTools));
            if (runConfig.codeInterpreterEnabled()) {
                toolSpecifications.addAll(ToolSpecifications.toolSpecificationsFrom(pythonSandboxTools));
            }

            var todoPlan = todoPlanner.plan(TodoPlanner.PlanRequest.builder()
                    .run(run)
                    .userId(userId)
                    .userGoal(userGoal)
                    .model(chatModel)
                    .toolSpecifications(toolSpecifications)
                    .endpointName(endpointName)
                    .endpointBaseUrl(endpointBaseUrl)
                    .modelName(modelName)
                    .build());

            WorkflowExecutionResult result = workflowExecutor.execute(LinearWorkflowExecutor.WorkflowRequest.builder()
                    .run(run)
                    .userId(userId)
                    .userGoal(userGoal)
                    .todoPlan(todoPlan)
                    .model(chatModel)
                    .toolSpecifications(toolSpecifications)
                    .endpointName(endpointName)
                    .endpointBaseUrl(endpointBaseUrl)
                    .modelName(modelName)
                    .build());

            if (result.isPaused()) {
                stateStore.markRunStatus(runId, AgentRunStatus.WAITING.name());
                runMapper.updateStatusWithTtl(runId, userId, AgentRunStatus.WAITING, eventService.nextInterruptedExpiresAt());
                return;
            }

            if (result.isSuccess()) {
                String snapshotJson = buildSnapshotJson(userGoal, todoPlan, result.getCompletedItems(), result.getFinalAnswer(), result.getContext(), AgentRunStatus.COMPLETED, runId);
                runMapper.updateSnapshot(runId, userId, AgentRunStatus.COMPLETED, snapshotJson, true, null);
                int totalCreditsConsumed = creditService.calculateRunTotalCredits(
                        runId,
                        userId,
                        observabilityService.loadObservabilityJson(runId, snapshotJson)
                );
                eventService.append(runId, userId, "WORKFLOW_COMPLETED", mapOf(
                        "answer", nvl(result.getFinalAnswer()),
                        "tool_calls_used", result.getToolCallsUsed(),
                        "totalCreditsConsumed", totalCreditsConsumed,
                        "total_credits_consumed", totalCreditsConsumed
                ));

                // 写入助手回复消息
                try {
                    String assistantMetaJson = messageService.buildMetaJson(
                            modelName,
                            endpointName,
                            null,
                            null
                    );
                    messageService.createAssistantMessage(runId, result.getFinalAnswer(), assistantMetaJson);

                    // 记录 MESSAGE_COMPLETED 事件
                    eventService.append(runId, userId, "MESSAGE_COMPLETED", mapOf(
                            "role", "assistant",
                            "content_preview", preview(result.getFinalAnswer(), 200),
                            "model", nvl(modelName),
                            "endpoint", nvl(endpointName)
                    ));
                } catch (Exception e) {
                    log.warn("Failed to create assistant message for runId={}, but continuing: {}", runId, e.getMessage());
                }

                creditService.recordRunConsumeLedger(runId, userId, totalCreditsConsumed);
                stateStore.markRunStatus(runId, AgentRunStatus.COMPLETED.name());
                return;
            }

            String reason = nvl(result.getFailureReason());
            String snapshotJson = buildSnapshotJson(userGoal, todoPlan, result.getCompletedItems(), result.getFinalAnswer(), result.getContext(), AgentRunStatus.FAILED, runId);
            runMapper.updateSnapshot(runId, userId, AgentRunStatus.FAILED, snapshotJson, true, reason);
            runMapper.updateStatusWithTtl(runId, userId, AgentRunStatus.FAILED, eventService.nextInterruptedExpiresAt());
            eventService.append(runId, userId, "WORKFLOW_FAILED", mapOf(
                    "error", reason,
                    "tool_calls_used", result.getToolCallsUsed()
            ));
            stateStore.markRunStatus(runId, AgentRunStatus.FAILED.name());
        } catch (Exception e) {
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("Execution error", e);
            observabilityService.recordFailure(runId, e.getClass().getSimpleName(), err);
            String failedSnapshotJson = observabilityService.attachObservabilityToSnapshot(runId, run.getSnapshotJson(), AgentRunStatus.FAILED);
            runMapper.updateSnapshot(runId, userId, AgentRunStatus.FAILED, failedSnapshotJson, true, err);
            runMapper.updateStatusWithTtl(runId, userId, AgentRunStatus.FAILED, eventService.nextInterruptedExpiresAt());
            eventService.append(runId, userId, "WORKFLOW_FAILED", mapOf("error", err));
            stateStore.markRunStatus(runId, AgentRunStatus.FAILED.name());
        } finally {
            AgentContext.clear();
        }
    }

    private String buildSnapshotJson(String userGoal,
                                     Object plan,
                                     Object completedItems,
                                     String answer,
                                     Object context,
                                     AgentRunStatus status,
                                     String runId) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("user_goal", userGoal);
        snapshot.put("plan", plan);
        snapshot.put("completed_items", completedItems);
        snapshot.put("answer", nvl(answer));
        snapshot.put("context", context == null ? Map.of() : context);
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            return observabilityService.attachObservabilityToSnapshot(runId, json, status);
        } catch (Exception e) {
            return observabilityService.attachObservabilityToSnapshot(runId, "{}", status);
        }
    }

    private Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private String resolveUserGoal(AgentRun run) {
        if (run == null || run.getId() == null) {
            return "";
        }
        try {
            AgentRunMessage latestUser = messageService.findLatestUserMessage(run.getId());
            if (latestUser != null && latestUser.getContent() != null && !latestUser.getContent().isBlank()) {
                return latestUser.getContent();
            }
        } catch (Exception e) {
            log.warn("Failed to resolve latest user message, fallback to ext: runId={}, err={}", run.getId(), e.getMessage());
        }
        return eventService.extractUserGoal(run.getExt());
    }

    private String preview(String content, int maxLen) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxLen) {
            return content;
        }
        return content.substring(0, maxLen) + "...";
    }
}

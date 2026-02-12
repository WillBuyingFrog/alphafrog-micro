package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.agent.tool.MarketDataTools;
import world.willfrog.agent.tool.PythonSandboxTools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunExecutor {

    private final AgentRunMapper runMapper;
    private final AgentEventService eventService;
    private final AgentAiServiceFactory aiServiceFactory;
    private final MarketDataTools marketDataTools;
    private final PythonSandboxTools pythonSandboxTools;
    private final world.willfrog.agent.tool.ToolRouter toolRouter;
    private final world.willfrog.agent.graph.ParallelGraphExecutor parallelGraphExecutor;
    private final AgentRunStateStore stateStore;
    private final AgentPromptService promptService;
    private final AgentObservabilityService observabilityService;
    private final AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    private final ObjectMapper objectMapper;

    @Async
    public void executeAsync(String runId) {
        try {
            execute(runId);
        } catch (Exception e) {
            log.error("Agent run execute failed: runId={}", runId, e);
        }
    }

    public void execute(String runId) {
        AgentRun run = runMapper.findById(runId);
        if (run == null) {
            log.warn("Agent run not found, ignore execute: {}", runId);
            return;
        }
        if (run.getStatus() == AgentRunStatus.CANCELED || run.getStatus() == AgentRunStatus.COMPLETED) {
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
            if (debugMode) {
                log.info("[agent-debug] run started: runId={}, endpoint={}, model={}, providerOrder={}",
                        runId, endpointName, modelName, providerOrder);
            }

            String userGoal = eventService.extractUserGoal(run.getExt());
            List<ToolSpecification> toolSpecifications = new ArrayList<>();
            toolSpecifications.addAll(ToolSpecifications.toolSpecificationsFrom(marketDataTools));
            toolSpecifications.addAll(ToolSpecifications.toolSpecificationsFrom(pythonSandboxTools));

            if (parallelGraphExecutor.isEnabled()) {
                executeDagMode(run, userId, userGoal, chatModel, toolSpecifications, endpointName, endpointBaseUrl, modelName);
                return;
            }

            eventService.append(runId, userId, "PARALLEL_FALLBACK_TO_SERIAL", Map.of(
                    "reason", "dag_mode_disabled",
                    "plan_valid_hint", stateStore.loadPlanValid(runId).map(String::valueOf).orElse("unknown"),
                    "state_status_hint", stateStore.loadRunStatus(runId).orElse("unknown")
            ));
            executeLegacySerialMode(run, userId, userGoal, chatModel, toolSpecifications, endpointName, endpointBaseUrl, modelName, debugMode);
        } catch (Exception e) {
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("Execution error", e);
            observabilityService.recordFailure(runId, e.getClass().getSimpleName(), err);
            String failedSnapshotJson = observabilityService.attachObservabilityToSnapshot(runId, run.getSnapshotJson(), AgentRunStatus.FAILED);
            runMapper.updateSnapshot(runId, userId, AgentRunStatus.FAILED, failedSnapshotJson, true, err);
            runMapper.updateStatusWithTtl(runId, userId, AgentRunStatus.FAILED, eventService.nextInterruptedExpiresAt());
            eventService.append(runId, userId, "FAILED", mapOf("error", err));
            stateStore.markRunStatus(runId, AgentRunStatus.FAILED.name());
        } finally {
            AgentContext.clear();
        }
    }

    private void executeDagMode(AgentRun run,
                                String userId,
                                String userGoal,
                                ChatLanguageModel chatModel,
                                List<ToolSpecification> toolSpecifications,
                                String endpointName,
                                String endpointBaseUrl,
                                String modelName) {
        boolean handled = parallelGraphExecutor.execute(
                run,
                userId,
                userGoal,
                chatModel,
                toolSpecifications,
                endpointName,
                endpointBaseUrl,
                modelName
        );
        if (!handled) {
            throw new IllegalStateException("dag_executor_unhandled");
        }
    }

    private void executeLegacySerialMode(AgentRun run,
                                         String userId,
                                         String userGoal,
                                         ChatLanguageModel chatModel,
                                         List<ToolSpecification> toolSpecifications,
                                         String endpointName,
                                         String endpointBaseUrl,
                                         String modelName,
                                         boolean debugMode) throws Exception {
        String runId = run.getId();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(promptService.agentRunSystemPrompt()));
        messages.add(new UserMessage(userGoal));

        int maxSteps = 15;
        String finalAnswer = "";
        String executionLog = "";

        for (int i = 0; i < maxSteps; i++) {
            if (!eventService.isRunnable(runId, userId)) {
                return;
            }
            observabilityService.addNodeCount(runId, 1);

            List<ChatMessage> requestMessages = new ArrayList<>(messages);
            long llmStartedAt = System.currentTimeMillis();
            Response<AiMessage> response = chatModel.generate(messages, toolSpecifications);
            long llmDurationMs = System.currentTimeMillis() - llmStartedAt;
            AiMessage aiMessage = response.content();
            if (debugMode) {
                log.info("[agent-debug] llm step done: runId={}, step={}, durationMs={}, hasToolCalls={}",
                        runId, i, llmDurationMs, aiMessage != null && aiMessage.hasToolExecutionRequests());
            }
            messages.add(aiMessage);
            String llmPhase = "tool_execution";
            if (i == 0) {
                llmPhase = AgentObservabilityService.PHASE_PLANNING;
            }
            if (!aiMessage.hasToolExecutionRequests()) {
                llmPhase = AgentObservabilityService.PHASE_SUMMARIZING;
            }
            Map<String, Object> llmRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                    endpointName,
                    endpointBaseUrl,
                    modelName,
                    requestMessages,
                    toolSpecifications,
                    Map.of(
                            "stage", "serial_tool_loop",
                            "step", i
                    )
            );
            observabilityService.recordLlmCall(
                    runId,
                    llmPhase,
                    response.tokenUsage(),
                    llmDurationMs,
                    endpointName,
                    modelName,
                    null,
                    llmRequestSnapshot,
                    aiMessage == null ? null : aiMessage.text()
            );

            if (aiMessage.hasToolExecutionRequests()) {
                for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                    String toolName = toolRequest.name();
                    String argsJson = toolRequest.arguments();

                    Map<String, Object> startPayload = new HashMap<>();
                    startPayload.put("tool_name", toolName);
                    startPayload.put("parameters", safeJson(argsJson));
                    eventService.append(runId, userId, "TOOL_CALL_STARTED", startPayload);

                    Map<String, Object> params = jsonToMap(argsJson);
                    if (debugMode) {
                        log.info("[agent-debug] tool invoke start: runId={}, step={}, tool={}, params={}",
                                runId, i, toolName, safeJsonForLog(params));
                    }
                    String result;
                    world.willfrog.agent.tool.ToolRouter.ToolInvocationResult invokeResult = null;
                    AgentContext.setPhase(AgentObservabilityService.PHASE_TOOL_EXECUTION);
                    try {
                        invokeResult = toolRouter.invokeWithMeta(toolName, params);
                        result = invokeResult.getOutput();
                    } finally {
                        AgentContext.clearPhase();
                    }

                    Map<String, Object> finishPayload = new HashMap<>();
                    finishPayload.put("tool_name", toolName);
                    finishPayload.put("success", invokeResult != null && invokeResult.isSuccess());
                    finishPayload.put("result_preview", result);
                    finishPayload.put("cache", toolRouter.toEventCachePayload(invokeResult));
                    eventService.append(runId, userId, "TOOL_CALL_FINISHED", finishPayload);
                    if (debugMode) {
                        log.info("[agent-debug] tool invoke done: runId={}, step={}, tool={}, success={}, cache={}, resultPreview={}",
                                runId,
                                i,
                                toolName,
                                invokeResult != null && invokeResult.isSuccess(),
                                toolRouter.toEventCachePayload(invokeResult),
                                preview(result));
                    }

                    messages.add(ToolExecutionResultMessage.from(toolRequest, result));
                    executionLog += "Tool: " + toolName + "\nResult: " + result + "\n\n";
                }
            } else {
                finalAnswer = aiMessage.text();
                break;
            }
        }

        if (finalAnswer == null || finalAnswer.isBlank()) {
            finalAnswer = "达到最大步骤数后仍未生成最终答案，任务已停止。";
        }

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("user_goal", userGoal);
        snapshot.put("execution_log", executionLog);
        snapshot.put("answer", finalAnswer);
        String snapshotJson = objectMapper.writeValueAsString(snapshot);
        snapshotJson = observabilityService.attachObservabilityToSnapshot(runId, snapshotJson, AgentRunStatus.COMPLETED);

        runMapper.updateSnapshot(runId, userId, AgentRunStatus.COMPLETED, snapshotJson, true, null);
        eventService.append(runId, userId, "COMPLETED", mapOf("answer", finalAnswer));
        stateStore.markRunStatus(runId, AgentRunStatus.COMPLETED.name());
    }

    private Map<String, Object> jsonToMap(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return jsonNodeToMap(node);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> jsonNodeToMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Map.of();
        }
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, Object> map = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode v = entry.getValue();
            if (v == null || v.isNull()) {
                map.put(entry.getKey(), null);
            } else if (v.isTextual()) {
                map.put(entry.getKey(), v.asText());
            } else if (v.isNumber()) {
                map.put(entry.getKey(), v.numberValue());
            } else if (v.isBoolean()) {
                map.put(entry.getKey(), v.asBoolean());
            } else {
                map.put(entry.getKey(), v.toString());
            }
        }
        return map;
    }

    private Object safeJson(String jsonText) {
        if (jsonText == null) {
            return null;
        }
        try {
            return objectMapper.readTree(jsonText);
        } catch (Exception e) {
            return jsonText;
        }
    }

    private String safeJsonForLog(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > 300) {
            return text.substring(0, 300);
        }
        return text;
    }

    private Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> map = new HashMap<>();
        map.put(k, v);
        return map;
    }
}

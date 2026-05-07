package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.config.RunStageConfig;
import world.willfrog.agent.config.StageLlmConfig;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.entity.AgentRunMessage;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.agent.tool.MarketDataTools;
import world.willfrog.agent.tool.PythonSandboxTools;
import world.willfrog.agent.tool.RagTools;
import world.willfrog.agent.tool.SearchTools;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoPlanner;
import world.willfrog.agent.workflow.WorkflowExecutionResult;
import world.willfrog.agent.workflow.WorkflowExecutor;
import world.willfrog.agent.workflow.WorkflowExecutorFactory;
import world.willfrog.agent.workflow.WorkflowRequest;

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
    private final RagTools ragTools;
    private final SearchTools searchTools;
    private final AgentRunStateStore stateStore;
    private final AgentObservabilityService observabilityService;
    private final AgentCreditService creditService;
    private final TodoPlanner todoPlanner;
    private final WorkflowExecutorFactory workflowExecutorFactory;
    private final AgentMessageService messageService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final AgentLlmProperties llmProperties;
    private final StageConfigResolver stageConfigResolver;
    private final StageConfigValidator stageConfigValidator;
    private final AgentFinalAnswerParser finalAnswerParser;
    private final AgentCitationService citationService;

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

            boolean captureLlmRequests = eventService.extractCaptureLlmRequests(run.getExt());
            boolean debugMode = eventService.extractDebugMode(run.getExt());
            AgentContext.setDebugMode(debugMode);

            // 解析阶段级 LLM 配置（客户端 Run 级 + local 合并）
            RunStageConfig stageConfig = stageConfigResolver.resolve(run.getExt());
            stageConfigValidator.validate(stageConfig);
            AgentContext.setStageConfig(stageConfig);

            // Execution 阶段模型解析。显式 run 请求优先，本地阶段配置只作为 fallback。
            String requestedEndpointName = eventService.extractEndpointName(run.getExt());
            String requestedModelName = eventService.extractModelName(run.getExt());
            StageLlmConfig execStageCfg = chooseEffectiveStageConfig(
                    requestedEndpointName,
                    requestedModelName,
                    stageConfig.getExecution(),
                    run.getExt(),
                    "execution");
            if (execStageCfg != null && execStageCfg.isValid()) {
                requestedEndpointName = execStageCfg.getEndpointName();
                requestedModelName = execStageCfg.getModelName();
            }
            AgentLlmResolver.ResolvedLlm resolvedLlm = aiServiceFactory.resolveLlm(requestedEndpointName, requestedModelName);
            String endpointName = resolvedLlm.endpointName();
            String modelName = resolvedLlm.modelName();
            String endpointBaseUrl = resolvedLlm.baseUrl();
            var userProviderOrder = eventService.extractOpenRouterProviderOrder(run.getExt());
            var providerOrder = mergeProviderOrder(userProviderOrder, resolvedLlm.validProviders());

            observabilityService.initializeRun(runId, endpointName, modelName, captureLlmRequests);
            ChatModel chatModel = aiServiceFactory.buildChatModelWithProviderOrder(resolvedLlm, providerOrder);

            // Planning 阶段模型解析：显式 stage_config_json.planning > run 请求 > 本地 planning。
            ChatModel planningModel;
            String planningEndpointName;
            String planningModelName;
            String planningEndpointBaseUrl;
            boolean useDedicatedPlanningModel = false;
            StageLlmConfig planningStageCfg = chooseEffectiveStageConfig(
                    eventService.extractEndpointName(run.getExt()),
                    eventService.extractModelName(run.getExt()),
                    stageConfig.getPlanning(),
                    run.getExt(),
                    "planning");
            if (planningStageCfg != null && planningStageCfg.isValid()) {
                // 客户端或 local 指定了 planning 专用模型
                AgentLlmResolver.ResolvedLlm planningResolvedLlm = aiServiceFactory.resolveLlm(
                        planningStageCfg.getEndpointName(), planningStageCfg.getModelName());
                // Bug 修复：planning 阶段应使用 planning 模型自己的 validProviders，而非 execution 阶段的
                var planningProviderOrder = mergeProviderOrder(userProviderOrder, planningResolvedLlm.validProviders());
                planningModel = aiServiceFactory.buildChatModelWithProviderOrder(planningResolvedLlm, planningProviderOrder);
                planningEndpointName = planningResolvedLlm.endpointName();
                planningModelName = planningResolvedLlm.modelName();
                planningEndpointBaseUrl = planningResolvedLlm.baseUrl();
                useDedicatedPlanningModel = true;
            } else {
                // 退化为使用 execution 模型
                planningModel = chatModel;
                planningEndpointName = endpointName;
                planningModelName = modelName;
                planningEndpointBaseUrl = endpointBaseUrl;
            }
            // 记录 planning 模型选择事件
            eventService.append(runId, userId, "PLANNING_MODEL_SELECTED", mapOf(
                    "endpoint", planningEndpointName,
                    "model", planningModelName,
                    "dedicatedConfig", useDedicatedPlanningModel
            ));

            String userGoal = resolveUserGoal(run);
            AgentEventService.RunConfig runConfig = eventService.extractRunConfig(run.getExt());
            AgentContext.setWebSearchEnabled(runConfig.webSearchEnabled());
            AgentContext.setWebSearchConfig(runConfig.webSearchConfig());

            eventService.append(runId, userId, "RUN_CONFIG_APPLIED", mapOf(
                    "webSearchEnabled", runConfig.webSearchEnabled(),
                    "webSearchBackend", runConfig.webSearchConfig().backend(),
                    "webSearchStrength", runConfig.webSearchConfig().strength(),
                    "webSearchSkipHotCache", runConfig.webSearchConfig().skipHotCache(),
                    "webSearchSkipRagPrefetch", runConfig.webSearchConfig().skipRagPrefetch(),
                    "webSearchMaxResults", runConfig.webSearchConfig().maxResults(),
                    "codeInterpreterEnabled", runConfig.codeInterpreterEnabled(),
                    "codeInterpreterMaxCredits", runConfig.codeInterpreterMaxCredits(),
                    "smartRetrievalEnabled", runConfig.smartRetrievalEnabled()
            ));
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
            toolSpecifications.addAll(ToolSpecifications.toolSpecificationsFrom(ragTools));
            if (runConfig.webSearchEnabled()) {
                toolSpecifications.addAll(ToolSpecifications.toolSpecificationsFrom(searchTools));
                eventService.append(runId, userId, "RUN_CAPABILITY_ENABLED", mapOf(
                        "capability", "webSearch",
                        "tools", List.of("searchWeb")
                ));
            }
            if (runConfig.codeInterpreterEnabled()) {
                toolSpecifications.addAll(ToolSpecifications.toolSpecificationsFrom(pythonSandboxTools));
            }

            // 解析执行模式
            String executionModeStr = eventService.extractExecutionMode(run.getExt());
            PlanExecutionMode executionMode;
            try {
                executionMode = PlanExecutionMode.valueOf(executionModeStr.toUpperCase());
            } catch (Exception e) {
                executionMode = PlanExecutionMode.AUTO;
            }
            boolean enablePlanPatch = eventService.extractEnablePlanPatch(run.getExt());
            Integer maxTodos = eventService.extractMaxTodos(run.getExt());
            log.info("Run {} execution mode: {}, maxTodos override: {}", runId, executionMode, maxTodos);

            var todoPlan = todoPlanner.plan(TodoPlanner.PlanRequest.builder()
                    .run(run)
                    .userId(userId)
                    .userGoal(userGoal)
                    .model(planningModel)
                    .toolSpecifications(toolSpecifications)
                    .endpointName(planningEndpointName)
                    .endpointBaseUrl(planningEndpointBaseUrl)
                    .modelName(planningModelName)
                    .executionMode(executionMode)
                    .maxTodos(maxTodos)
                    .build());
            AgentContext.setExtractedEntities(todoPlan.getExtractedEntities());

            // 根据 Plan 特征选择执行器（LinearWorkflowExecutor 或 DagWorkflowExecutor）
            WorkflowExecutor selectedExecutor = workflowExecutorFactory.select(todoPlan);

            // 设置 Execution 阶段 reasoning 配置（Planning 阶段可能已清除）
            String executionReasoningEffort = (execStageCfg != null && execStageCfg.getReasoningEffort() != null)
                    ? execStageCfg.getReasoningEffort()
                    : resolveExecutionReasoningEffort();
            if (executionReasoningEffort != null) {
                AgentContext.setReasoningEffort(executionReasoningEffort);
            }

            WorkflowExecutionResult result = selectedExecutor.execute(WorkflowRequest.builder()
                    .run(run)
                    .userId(userId)
                    .userGoal(userGoal)
                    .plan(todoPlan)
                    .model(chatModel)
                    .toolSpecifications(toolSpecifications)
                    .endpointName(endpointName)
                    .endpointBaseUrl(endpointBaseUrl)
                    .modelName(modelName)
                    .extractedEntities(todoPlan.getExtractedEntities())
                    .enablePlanPatch(enablePlanPatch)
                    .build());

            if (result.isPaused()) {
                stateStore.markRunStatus(runId, AgentRunStatus.WAITING.name());
                runMapper.updateStatusWithTtl(runId, userId, AgentRunStatus.WAITING, eventService.nextInterruptedExpiresAt());
                return;
            }

            if (result.isSuccess()) {
                String snapshotJson = buildSnapshotJson(userGoal, todoPlan, result.getCompletedItems(), result.getFinalAnswer(), result.getContext(), result.getCitationMap(), AgentRunStatus.COMPLETED, runId);
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
            String snapshotJson = buildSnapshotJson(userGoal, todoPlan, result.getCompletedItems(), result.getFinalAnswer(), result.getContext(), result.getCitationMap(), AgentRunStatus.FAILED, runId);
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
                                     AgentCitationService.CitationMap citationMap,
                                     AgentRunStatus status,
                                     String runId) {
        Map<String, Object> snapshot = new HashMap<>();
        AgentCitationService.CitationMap safeCitationMap = citationMap == null ? AgentCitationService.CitationMap.empty() : citationMap;
        AgentFinalAnswerParser.ParsedAnswer parsedAnswer = finalAnswerParser.parse(answer, safeCitationMap);
        snapshot.put("user_goal", userGoal);
        snapshot.put("plan", plan);
        snapshot.put("completed_items", completedItems);
        snapshot.put("citation_map", citationService.toSnapshotMap(safeCitationMap));
        snapshot.put("answer", nvl(parsedAnswer.answerMarkdown()));
        snapshot.putAll(parsedAnswer.toSnapshotFields());
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

    /**
     * 合并用户指定的 provider 列表与配置中的 validProviders。
     * 用户指定的 provider 优先放在前面，validProviders 中不重复的追加在后面作为兜底。
     */
    private List<String> mergeProviderOrder(List<String> userProviders, List<String> validProviders) {
        if (validProviders == null || validProviders.isEmpty()) {
            return userProviders == null ? List.of() : userProviders;
        }
        if (userProviders == null || userProviders.isEmpty()) {
            return validProviders;
        }
        List<String> merged = new ArrayList<>(userProviders);
        for (String vp : validProviders) {
            if (!merged.contains(vp)) {
                merged.add(vp);
            }
        }
        return merged;
    }

    private StageLlmConfig chooseEffectiveStageConfig(String requestedEndpointName,
                                                      String requestedModelName,
                                                      StageLlmConfig fallback,
                                                      String extJson,
                                                      String stageName) {
        StageLlmConfig clientStage = parseClientStageConfig(extJson, stageName);
        StageLlmConfig effective = new StageLlmConfig();
        effective.setEndpointName(firstNonBlank(
                clientStage == null ? null : clientStage.getEndpointName(),
                firstNonBlank(requestedEndpointName, fallback == null ? null : fallback.getEndpointName())));
        effective.setModelName(firstNonBlank(
                clientStage == null ? null : clientStage.getModelName(),
                firstNonBlank(requestedModelName, fallback == null ? null : fallback.getModelName())));
        effective.setReasoningEffort(firstNonBlank(
                clientStage == null ? null : clientStage.getReasoningEffort(),
                fallback == null ? null : fallback.getReasoningEffort()));
        effective.setTemperature(clientStage != null && clientStage.getTemperature() != null
                ? clientStage.getTemperature()
                : (fallback == null ? null : fallback.getTemperature()));
        effective.setMaxTokens(clientStage != null && clientStage.getMaxTokens() != null
                ? clientStage.getMaxTokens()
                : (fallback == null ? null : fallback.getMaxTokens()));
        return effective;
    }

    private StageLlmConfig parseClientStageConfig(String extJson, String stageName) {
        if (extJson == null || extJson.isBlank() || stageName == null || stageName.isBlank()) {
            return null;
        }
        try {
            var root = objectMapper.readTree(extJson);
            var stageNode = root.get("stage_config_json");
            if (stageNode == null || stageNode.isNull()) {
                return null;
            }
            if (stageNode.isTextual()) {
                stageNode = objectMapper.readTree(stageNode.asText());
            }
            var phaseNode = stageNode.get(stageName);
            if (phaseNode == null || !phaseNode.isObject()) {
                return null;
            }
            StageLlmConfig config = objectMapper.treeToValue(phaseNode, StageLlmConfig.class);
            return hasAnyStageField(config) ? config : null;
        } catch (Exception e) {
            log.warn("解析 stage_config_json.{} 失败，将使用 run 请求与本地 fallback 合并: {}", stageName, e.getMessage());
            return null;
        }
    }

    private String firstNonBlank(String first, String second) {
        return hasText(first) ? first.trim() : (hasText(second) ? second.trim() : null);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean hasAnyStageField(StageLlmConfig config) {
        return config != null
                && (hasText(config.getEndpointName())
                || hasText(config.getModelName())
                || hasText(config.getReasoningEffort())
                || config.getTemperature() != null
                || config.getMaxTokens() != null);
    }

    /**
     * 解析 Execution 阶段的 OpenRouter reasoning (thinking) 配置。
     * <p>优先从热加载配置读取，其次从静态配置读取。</p>
     *
     * @return reasoning effort 值，或 null 表示不配置（使用模型默认行为）
     */
    private String resolveExecutionReasoningEffort() {
        // 1. 尝试从 local config (热加载) 读取
        String effort = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getExecution)
                .map(AgentLlmProperties.Execution::getReasoning)
                .map(AgentLlmProperties.Reasoning::resolveEffort)
                .orElse(null);
        if (effort != null) return effort;

        // 2. 从 base properties 读取
        if (llmProperties.getRuntime() != null && llmProperties.getRuntime().getExecution() != null
                && llmProperties.getRuntime().getExecution().getReasoning() != null) {
            return llmProperties.getRuntime().getExecution().getReasoning().resolveEffort();
        }
        return null;
    }
}

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

/**
 * agentLangchainService 的 run 级总控流水线。
 *
 * <p>面试时可以把这个类理解成“一个 AgentRun 从创建后到完成/失败落库”的主线剧本：
 * 先恢复 run 记录和运行上下文，再解析 planning（规划）、execution（执行）、
 * final answer（最终答案）三个阶段要用的模型，接着调用 {@link LangchainAiPlanner}
 * 生成 todo（任务项）计划，之后根据计划选择
 * {@link LangchainLinearWorkflowExecutor} 或 {@link LangchainDagWorkflowExecutor} 执行，
 * 最后把答案、事件、运行快照和可观测数据写回共享存储。</p>
 *
 * <p>这个类本身不直接和大模型对话，也不直接执行工具；它负责把“模型、prompt、工具目录、
 * 状态机、可观测性、取消/暂停控制”串成一个完整业务流程。具体规划逻辑在
 * {@link LangchainAiPlanner}，单个 todo 的工具循环在 {@link LangchainTodoNodeExecutor}。</p>
 */
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
        // 重新从数据库读取 run，而不是完全相信入口传入的对象。
        // createRun 之后到异步线程真正执行之间，run 可能已经被取消、更新或存活时间（TTL）状态变化。
        AgentRun run = runMapper.findById(initialRun.getId());
        if (run == null) {
            log.warn("LangChain run not found, skip: {}", initialRun.getId());
            return;
        }
        String runId = run.getId();
        String userId = run.getUserId();
        String userGoal = "";
        try {
            // AgentContext 是本次 LLM/tool 调用链的线程级上下文。
            // 后续 OpenRouterProviderRoutedChatModel、ToolRouterToolProvider、可观测服务都会从这里取 runId/userId/phase。
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

            // 阶段模型解析是 run 级配置落地的入口：
            // 用户请求、stage_config_json、Nacos agent-llm 配置会在这里合并成 planning/execution/final 三个 ChatModel。
            LangchainRunStageModelResolver.StageModels stageModels = stageModelResolver.resolve(run);
            boolean captureLlmRequests = eventService.extractCaptureLlmRequests(run.getExt());
            AgentObservabilityService observabilityService = observabilityServiceProvider.getIfAvailable();
            if (observabilityService != null) {
                // initializeRun 只初始化观测容器和默认模型信息，实际每次 LLM/tool 调用会在各自组件里继续追加 trace。
                observabilityService.initializeRun(
                        runId,
                        firstNonBlank(stageModels.planningEndpointName(), eventService.extractEndpointName(run.getExt())),
                        firstNonBlank(stageModels.planningModelName(), eventService.extractModelName(run.getExt())),
                        captureLlmRequests);
            }
            // follow-up 复用同一个 run：这里把历史对话压缩内容和当前用户目标拆出来，
            // 让 planning（规划）阶段既能看到追问上下文，又不会把旧消息原样塞满上下文窗口。
            LangchainFollowUpContextSupport.ExecutionContext executionContext = followUpContextSupport.resolve(run);
            userGoal = executionContext.userGoal();
            String dialogueContext = executionContext.dialogueContext();
            AgentEventService.RunConfig runConfig = eventService.extractRunConfig(run.getExt());
            AgentContext.setWebSearchEnabled(runConfig.webSearchEnabled());
            AgentContext.setWebSearchConfig(runConfig.webSearchConfig());

            // 这里先解析“当前 run 允许暴露给模型的工具列表”，planning（规划）阶段会把这些工具能力写进 prompt，
            // execution（执行）阶段也会用同一套 ToolSpecification 注册到 LC4j（LangChain4j）AiServices。
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

            // planning（规划）阶段只负责把用户目标变成 todo plan（任务计划）。
            // 计划是否跑 DAG（有向无环图）是后置决策：planner 可以输出 AUTO/LINEAR/DAG，最终由 LangchainWorkflowRouting 统一裁决。
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

            // agentLangchainService 的“Linear”（线性执行）历史命名保留下来了，但这里已经是混合执行入口：
            // 简单串行任务走 linear executor；存在依赖图或 planner 明确要求时走 DAG executor。
            LangchainLinearWorkflowResult result = useDag
                    ? dagWorkflowExecutor.executePlanned(workflowRequest, plan)
                    : linearWorkflowExecutor.executePlanned(workflowRequest, plan);

            // cancel/pause 可能发生在 todo 执行和最终落库之间。
            // 这里再次检查，避免用户已经取消后 pipeline 又把 run 覆盖成 COMPLETED/FAILED。
            if (result.isInterrupted() || abortIfStopped(runId, userId, "before_persist")) {
                log.info("LangChain run {} stopped before persist (interrupted={}, reason={})",
                        runId, result.isInterrupted(), result.getFailureReason());
                return;
            }

            runMapper.updatePlanJson(runId, userId, writeJson(result.getPlan()));
            if (result.isSuccess()) {
                // 成功路径的状态写入顺序：运行快照带可观测摘要 → 数据库状态 COMPLETED → Redis 控制状态 → 事件 → assistant 消息。
                // 这样前端先看到终态时，通常也能拿到完整答案和可观测摘要。
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
            // 所有未被 workflow result（工作流结果）显式表达的异常都会收敛到统一失败出口，
            // 由 LangchainFailureMapper 决定前端事件类型和 observability failure type（可观测失败类型）。
            publishFailure(runId, userId, userGoal,
                    LangchainLinearWorkflowResult.builder()
                            .success(false)
                            .failureReason(e.getMessage())
                            .toolCallsUsed(0)
                            .build(),
                    e);
        } finally {
            // 异步线程会被线程池复用，必须清理 ThreadLocal（线程本地变量），避免下一个 run 继承上一个 run 的 phase/todo/provider 信息。
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
        // LC4j（LangChain4j）ToolProvider 是动态的：同一个服务会根据 run 配置决定是否暴露 web search（网页搜索）、
        // code interpreter（代码解释器）等能力。planning prompt 和 execution tool-calling（执行阶段工具调用）
        // 必须使用同一份 ToolSpecification，避免“计划里有工具但执行时没有”。
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
        // snapshot 是给前端、调试脚本和恢复流程看的业务快照；它不是完整观测明细。
        // 大体积 LLM trace 仍由 AgentObservabilityService 管理，必要时通过 observability/full 单独拉取。
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
            // messages 表是 follow-up 的上下文来源之一；最终答案不仅要进 snapshot，也要作为 assistant message 留存。
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
        // FailureMapper 把底层异常字符串翻译成业务事件。
        // 例如 budget（预算）超限、工具错误、重复工具调用、普通 workflow failed（工作流失败）
        // 会映射成不同 event payload（事件载荷），方便 matrix（矩阵测试脚本）和前端判断。
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
            // 失败原因既写业务 snapshot，也写 observability failure（可观测失败记录），
            // 后者用于 trace（调用轨迹）/timeline（时间线）聚合和 matrix（矩阵测试脚本）诊断。
            observabilityService.recordFailure(
                    runId,
                    isBlank(observabilityFailureType) ? "WorkflowFailed" : observabilityFailureType,
                    failureReason);
        }
        return observabilityService.attachObservabilityToSnapshot(runId, snapshot, status);
    }
}

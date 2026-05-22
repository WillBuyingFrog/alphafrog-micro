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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
import world.willfrog.agent.workflow.TodoPlan;
import world.willfrog.agent.workflow.TodoPlanner;
import world.willfrog.agent.workflow.WorkflowExecutionResult;
import world.willfrog.agent.workflow.WorkflowExecutor;
import world.willfrog.agent.workflow.WorkflowExecutorFactory;
import world.willfrog.agent.workflow.WorkflowRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent Run 的主执行器，负责串联一次 Agent Run 从创建到完成的全部生命周期。
 *
 * <h3>核心执行流程（{@link #doExecute}）</h3>
 * <ol>
 *   <li><b>加载与校验</b>：从 DB 加载 run，检查是否为可执行状态。</li>
 *   <li><b>上下文初始化</b>：填充 {@link AgentContext}（runId、userId、debugMode 等）。</li>
 *   <li><b>阶段模型配置</b>：分别解析 Planning、Execution、Final-Answer 三个阶段的
 *       LLM 配置（endpoint、model、reasoningEffort、temperature 等）。配置优先级为：
 *       客户端 run 请求的 stage_config_json &gt; 顶层请求参数 &gt; 本地 agent-llm 配置。</li>
 *   <li><b>工具注册</b>：根据 run 的能力开关（webSearch、codeInterpreter 等）将工具
 *       注册为 LangChain4j ToolSpecification。</li>
 *   <li><b>Plan 生成</b>：调用 {@link TodoPlanner} 让 LLM 生成 Todo Plan，
 *       同时由 Planner 自动选择执行模式（AUTO / LINEAR / DAG）。</li>
 *   <li><b>执行器选择</b>：通过 {@link WorkflowExecutorFactory} 根据 Plan 特征选择
 *       {@link world.willfrog.agent.workflow.LinearWorkflowExecutor} 或
 *       {@link world.willfrog.agent.workflow.DagWorkflowExecutor}。</li>
 *   <li><b>工作流执行</b>：调用选中执行器的 {@code execute} 方法，得到
 *       {@link WorkflowExecutionResult}。</li>
 *   <li><b>结果持久化</b>：构建 run snapshot JSON（含最终答案、引用表、结构化答案等），
 *       写入 DB，发送完成事件，更新缓存状态。</li>
 * </ol>
 *
 * <h3>并发与监控</h3>
 * 通过 {@code @Async} 异步执行，通过 Micrometer 上报活跃 run 数和执行耗时。
 *
 * @see world.willfrog.agent.workflow.LinearWorkflowExecutor
 * @see world.willfrog.agent.workflow.DagWorkflowExecutor
 * @see StageConfigResolver
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunExecutor {

    /** Agent Run 持久化 Mapper */
    private final AgentRunMapper runMapper;
    /** 事件流服务，负责追加 run 事件和从 ext JSON 中提取配置参数 */
    private final AgentEventService eventService;
    /** ChatModel 工厂，封装 endpoint 解析、provider 路由和 ChatModel 构造 */
    private final AgentAiServiceFactory aiServiceFactory;
    /** 行情数据工具（指数、股票、基金查询等） */
    private final MarketDataTools marketDataTools;
    /** Python 沙箱执行工具 */
    private final PythonSandboxTools pythonSandboxTools;
    /** RAG 检索工具 */
    private final RagTools ragTools;
    /** 网页搜索工具 */
    private final SearchTools searchTools;
    /** Run 级 Redis 状态缓存（运行状态、工作流中间态） */
    private final AgentRunStateStore stateStore;
    /** 观测数据服务，管理 LLM/工具调用的 trace 记录和观测视图 */
    private final AgentObservabilityService observabilityService;
    /** 额度消费服务，计算并记录 run 的资源消耗 */
    private final AgentCreditService creditService;
    /** Todo Plan 生成器，让 LLM 将用户目标拆解为 Todo 列表 */
    private final TodoPlanner todoPlanner;
    /** 执行器工厂，基于 Plan 特征选择 LinearWorkflowExecutor 或 DagWorkflowExecutor */
    private final WorkflowExecutorFactory workflowExecutorFactory;
    /** 消息服务，管理 run 内的用户/助手消息 */
    private final AgentMessageService messageService;
    /** JSON 序列化/反序列化 */
    private final ObjectMapper objectMapper;
    /** Micrometer 指标注册中心，用于上报 run 执行指标 */
    private final MeterRegistry meterRegistry;
    /** 本地 LLM 热加载配置加载器（从本地动态配置文件读取，支持不重启热更新） */
    private final AgentLlmLocalConfigLoader localConfigLoader;
    /** 静态 LLM 配置属性（来自 application.yml，作为热加载配置的 fallback） */
    private final AgentLlmProperties llmProperties;
    /** 阶段配置解析器，负责将 run ext JSON 与本地配置合并得到 RunStageConfig */
    private final StageConfigResolver stageConfigResolver;
    /** 阶段配置校验器，校验合并后的 RunStageConfig 关键字段不为空 */
    private final StageConfigValidator stageConfigValidator;
    /** 最终答案解析器，将 LLM 输出的最终答案解析为 Markdown + 结构化答案 */
    private final AgentFinalAnswerParser finalAnswerParser;
    /** 引用来源服务，负责从已完成任务中提取、去重、编号引用来源 */
    private final AgentCitationService citationService;
    /** 简单单工具查询 fast-path，命中时跳过 Planning/ReAct。 */
    private final AgentSimpleToolFastPathService simpleToolFastPathService;
    @Qualifier("agentRunTaskExecutor")
    private final Executor agentRunTaskExecutor;

    /** 当前正在执行的 Agent Run 数量（用于 Micrometer Gauge） */
    private final AtomicInteger activeRuns = new AtomicInteger(0);
    /** Run 执行耗时 Timer（上报 P50/P95/P99 分位数） */
    private Timer runDurationTimer;

    /**
     * 初始化 Micrometer 指标：
     * <ul>
     *   <li>{@code run.active} — 当前活跃 run 数</li>
     *   <li>{@code run.duration} — run 执行耗时分布</li>
     * </ul>
     */
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

    /**
     * 异步执行 Agent Run。
     *
     * <p>通过 Spring {@code @Async} 在独立线程中执行，不阻塞调用方（通常是 Dubbo 线程）。
     * 内部捕获所有异常，避免异步线程静默死亡。</p>
     *
     * @param runId 待执行的 Agent Run ID
     */
    @Async("agentRunTaskExecutor")
    public void executeAsync(String runId) {
        AgentRun run = runMapper.findById(runId);
        String status = run != null && run.getStatus() != null ? run.getStatus().name() : "unknown";
        int activeCount = -1;
        int queueSize = -1;
        if (agentRunTaskExecutor instanceof ThreadPoolTaskExecutor taskExecutor) {
            activeCount = taskExecutor.getActiveCount();
            queueSize = taskExecutor.getThreadPoolExecutor().getQueue().size();
        }
        log.info("Agent run executeAsync entered: runId={} status={} thread={} activeCount={} queueSize={}",
                runId, status, Thread.currentThread().getName(), activeCount, queueSize);
        try {
            execute(runId);
        } catch (Exception e) {
            log.error("Agent run execute failed: runId={}", runId, e);
        }
    }

    /**
     * 同步执行 Agent Run，包裹 Micrometer 指标记录。
     *
     * <p>在进入前递增活跃 run 计数，退出后在 finally 中递减计数并记录耗时 histogram。
     * 实际执行逻辑委托给 {@link #doExecute(String)}。</p>
     *
     * @param runId 待执行的 Agent Run ID
     */
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

    /**
     * Agent Run 核心执行逻辑，串联从 run 加载到结果持久化的全流程。
     *
     * <h4>执行流程概览</h4>
     * <ol>
     *   <li>从 DB 加载 run，跳过已终态（CANCELED/COMPLETED/EXPIRED）的 run。</li>
     *   <li>初始化 {@link AgentContext}：runId、userId、debugMode。</li>
     *   <li>解析阶段 LLM 配置：Planning / Execution / Final-Answer 三阶段的
     *       endpoint、model、reasoningEffort 等，合并客户端请求和本地配置。</li>
     *   <li>构建 ChatModel（含 OpenRouter provider 路由）。</li>
     *   <li>注册可用工具为 ToolSpecification，根据 run 能力开关决定启用哪些工具。</li>
     *   <li>调用 {@link TodoPlanner} 生成 Todo Plan。</li>
     *   <li>通过 {@link WorkflowExecutorFactory} 选择执行器（线性或 DAG）。</li>
     *   <li>调用执行器的 {@code execute} 方法执行工作流。</li>
     *   <li>根据执行结果构建 snapshot JSON 并持久化到 DB，写入事件流和 Redis 缓存。</li>
     * </ol>
     *
     * @param runId 待执行的 Agent Run ID
     */
    private void doExecute(String runId) {
        // ── 1. 加载 run 并检查是否可执行 ──
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
            // ── 2. 初始化 AgentContext ──
            AgentContext.setRunId(runId);
            AgentContext.setUserId(userId);

            // 再次检查是否可执行（eventService 内可能额外检查 run 状态约束）
            if (!eventService.isRunnable(runId, userId)) {
                return;
            }

            // 更新状态为 EXECUTING 并发送事件
            runMapper.updateStatus(runId, userId, AgentRunStatus.EXECUTING);
            eventService.append(runId, userId, "EXECUTION_STARTED", mapOf("run_id", runId));
            stateStore.markRunStatus(runId, AgentRunStatus.EXECUTING.name());

            // ── 3. 解析 run ext 中的通用控制参数 ──
            // 是否记录完整 LLM 请求详情（用于调试/观测）
            boolean captureLlmRequests = eventService.extractCaptureLlmRequests(run.getExt());
            // 是否为调试模式（跳过权限校验等）
            boolean debugMode = eventService.extractDebugMode(run.getExt());
            AgentContext.setDebugMode(debugMode);

            // ── 4. 解析阶段级 LLM 配置 ──
            // RunStageConfig 包含 planning/execution/final_answer 三个阶段的
            // StageLlmConfig，每个包含 endpointName、modelName、reasoningEffort、
            // temperature、maxTokens、providerOrder 等字段。
            RunStageConfig stageConfig = stageConfigResolver.resolve(run.getExt());
            stageConfigValidator.validate(stageConfig);
            AgentContext.setStageConfig(stageConfig);

            // ── 4a. 解析 Execution 阶段的有效配置 ──
            // 优先级：显式 stage_config_json.execution > 顶层请求参数 > 本地 agent-llm execution 配置
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
            // 解析 endpoint/model -> 得到 baseUrl 和该 endpoint 支持的 provider 列表
            AgentLlmResolver.ResolvedLlm resolvedLlm = aiServiceFactory.resolveLlm(requestedEndpointName, requestedModelName);
            String endpointName = resolvedLlm.endpointName();
            String modelName = resolvedLlm.modelName();
            String endpointBaseUrl = resolvedLlm.baseUrl();
            // 用户可通过 run 请求指定 OpenRouter provider 偏好顺序
            var userProviderOrder = eventService.extractOpenRouterProviderOrder(run.getExt());
            // 合并用户指定 + endpoint 自带 validProviders，去重
            var providerOrder = mergeProviderOrder(resolveStageProviderOrder(execStageCfg, userProviderOrder), resolvedLlm.validProviders());

            // 初始化 run 级观测记录
            observabilityService.initializeRun(runId, endpointName, modelName, captureLlmRequests);
            // 构建带 provider 路由的 ChatModel（用于 Execution 阶段）
            ChatModel chatModel = aiServiceFactory.buildChatModelWithProviderOrder(
                    resolvedLlm, providerOrder, execStageCfg.getMaxTokens());

            // ── 4b. 解析 Planning 阶段的专用模型配置 ──
            // Planning 可以使用与 Execution 不同的模型
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
                // 客户端或本地配置指定了 planning 专用模型
                AgentLlmResolver.ResolvedLlm planningResolvedLlm = aiServiceFactory.resolveLlm(
                        planningStageCfg.getEndpointName(), planningStageCfg.getModelName());
                // 注意：planning 阶段使用 planning 模型自己的 validProviders
                var planningProviderOrder = mergeProviderOrder(
                        resolveStageProviderOrder(planningStageCfg, userProviderOrder),
                        planningResolvedLlm.validProviders());
                planningModel = aiServiceFactory.buildChatModelWithProviderOrder(
                        planningResolvedLlm, planningProviderOrder, planningStageCfg.getMaxTokens());
                planningEndpointName = planningResolvedLlm.endpointName();
                planningModelName = planningResolvedLlm.modelName();
                planningEndpointBaseUrl = planningResolvedLlm.baseUrl();
                useDedicatedPlanningModel = true;
            } else {
                // 未配置 planning 专用模型，退化为使用 execution 模型
                planningModel = chatModel;
                planningEndpointName = endpointName;
                planningModelName = modelName;
                planningEndpointBaseUrl = endpointBaseUrl;
            }
            eventService.append(runId, userId, "PLANNING_MODEL_SELECTED", mapOf(
                    "endpoint", planningEndpointName,
                    "model", planningModelName,
                    "dedicatedConfig", useDedicatedPlanningModel
            ));

            // ── 4c. 解析 Final-Answer 阶段的专用模型配置 ──
            // Final-Answer 是生成最终回答的阶段，同样可以使用独立模型
            ChatModel finalAnswerModel = null;
            StageLlmConfig finalAnswerStageCfg = chooseEffectiveStageConfig(
                    eventService.extractEndpointName(run.getExt()),
                    eventService.extractModelName(run.getExt()),
                    stageConfig.getFinalAnswer(),
                    run.getExt(),
                    "final_answer");
            // 仅当 final-answer 阶段确实配置了有效字段时，才解析独立模型
            if (hasAnyStageField(stageConfig.getFinalAnswer()) && finalAnswerStageCfg != null && finalAnswerStageCfg.isValid()) {
                AgentLlmResolver.ResolvedLlm finalAnswerResolvedLlm = aiServiceFactory.resolveLlm(
                        finalAnswerStageCfg.getEndpointName(), finalAnswerStageCfg.getModelName());
                var finalAnswerProviderOrder = mergeProviderOrder(
                        resolveStageProviderOrder(finalAnswerStageCfg, userProviderOrder),
                        finalAnswerResolvedLlm.validProviders());
                finalAnswerModel = aiServiceFactory.buildChatModelWithProviderOrder(
                        finalAnswerResolvedLlm, finalAnswerProviderOrder, finalAnswerStageCfg.getMaxTokens());
                eventService.append(runId, userId, "FINAL_ANSWER_MODEL_SELECTED", mapOf(
                        "endpoint", finalAnswerResolvedLlm.endpointName(),
                        "model", finalAnswerResolvedLlm.modelName(),
                        "dedicatedConfig", true
                ));
            }

            // ── 5. 解析用户目标与 run 能力配置 ──
            String userGoal = resolveUserGoal(run);
            AgentEventService.RunConfig runConfig = eventService.extractRunConfig(run.getExt());
            // 将网页搜索能力开关和配置写入 AgentContext，供后续工具调用检查
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
            // smartRetrieval 能力尚未实现后端，写入占位事件告知前端
            if (runConfig.smartRetrievalEnabled()) {
                eventService.append(runId, userId, "RUN_CAPABILITY_PLACEHOLDER", mapOf(
                        "capability", "smartRetrieval",
                        "requested", true,
                        "available", false,
                        "reason", "backend_tool_not_implemented_yet"
                ));
            }

            // ── 6. 注册工具 ──
            // 根据 run 能力开关决定哪些工具对 LLM 可见
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

            // ── 6a. 简单单工具查询 fast-path ──
            // 命中高确定性的单工具查询时，跳过完整 Planning + ReAct，降低简单问题延迟。
            var fastPathDecision = simpleToolFastPathService.decide(userGoal, toolSpecifications);
            if (fastPathDecision.isPresent()) {
                AgentSimpleToolFastPathService.FastPathDecision decision = fastPathDecision.get();
                if (decision.selected()) {
                    eventService.append(runId, userId, "FAST_PATH_SELECTED", mapOf(
                            "tool", decision.toolName(),
                            "params", decision.params()
                    ));
                    WorkflowExecutionResult fastPathResult = simpleToolFastPathService.execute(decision);
                    eventService.append(runId, userId,
                            fastPathResult.isSuccess() ? "FAST_PATH_COMPLETED" : "FAST_PATH_FAILED",
                            mapOf("tool", decision.toolName()));
                    TodoPlan fastPathPlan = TodoPlan.builder()
                            .analysis("fast_path:" + decision.toolName())
                            .items(List.of())
                            .extractedEntities(List.of())
                            .build();
                    handleWorkflowResult(run, userId, userGoal, endpointName, modelName, fastPathPlan, fastPathResult);
                    return;
                }
                eventService.append(runId, userId, "FAST_PATH_SKIPPED", mapOf("reason", decision.reason()));
            }

            // ── 7. 解析执行模式 ──
            // 执行模式决定走线性还是 DAG 执行器。AUTO 模式下由 Planner 自动判断。
            String executionModeStr = eventService.extractExecutionMode(run.getExt());
            PlanExecutionMode executionMode;
            try {
                executionMode = PlanExecutionMode.valueOf(executionModeStr.toUpperCase());
            } catch (Exception e) {
                executionMode = PlanExecutionMode.AUTO;
            }
            // Plan Patch 允许在 todo 失败时自动修复 Plan 并重试
            boolean enablePlanPatch = eventService.extractEnablePlanPatch(run.getExt());
            Integer maxTodos = eventService.extractMaxTodos(run.getExt());
            log.info("Run {} execution mode: {}, maxTodos override: {}", runId, executionMode, maxTodos);

            // ── 8. 生成 Todo Plan ──
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
            // 记录 Planner 从用户目标中提取的实体（如"沪深300"、"中证500"），
            // 后续搜索证据判断等环节会用到
            AgentContext.setExtractedEntities(todoPlan.getExtractedEntities());

            // ── 9. 选择执行器 ──
            WorkflowExecutor selectedExecutor = workflowExecutorFactory.select(todoPlan);

            // 设置 Execution 阶段的 reasoning effort 配置（Planning 阶段可能已清除）
            String executionReasoningEffort = (execStageCfg != null && execStageCfg.getReasoningEffort() != null)
                    ? execStageCfg.getReasoningEffort()
                    : resolveExecutionReasoningEffort();
            if (executionReasoningEffort != null) {
                AgentContext.setReasoningEffort(executionReasoningEffort);
            }

            // ── 10. 执行工作流 ──
            WorkflowExecutionResult result = selectedExecutor.execute(WorkflowRequest.builder()
                    .run(run)
                    .userId(userId)
                    .userGoal(userGoal)
                    .plan(todoPlan)
                    .model(chatModel)
                    .finalAnswerModel(finalAnswerModel)
                    .finalAnswerReasoningEffort(finalAnswerStageCfg == null ? null : finalAnswerStageCfg.getReasoningEffort())
                    .toolSpecifications(toolSpecifications)
                    .endpointName(endpointName)
                    .endpointBaseUrl(endpointBaseUrl)
                    .modelName(modelName)
                    .extractedEntities(todoPlan.getExtractedEntities())
                    .enablePlanPatch(enablePlanPatch)
                    .build());

            // ── 11. 处理执行结果 ──
            handleWorkflowResult(run, userId, userGoal, endpointName, modelName, todoPlan, result);
        } catch (Exception e) {
            // ── 未预期的异常 ──
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("Execution error", e);
            observabilityService.recordFailure(runId, e.getClass().getSimpleName(), err);
            // 即使异常，也尽可能基于已有观测数据构建失败 snapshot
            String failedSnapshotJson = observabilityService.attachObservabilityToSnapshot(runId, run.getSnapshotJson(), AgentRunStatus.FAILED);
            runMapper.updateSnapshot(runId, userId, AgentRunStatus.FAILED, failedSnapshotJson, true, err);
            runMapper.updateStatusWithTtl(runId, userId, AgentRunStatus.FAILED, eventService.nextInterruptedExpiresAt());
            eventService.append(runId, userId, "WORKFLOW_FAILED", mapOf("error", err));
            stateStore.markRunStatus(runId, AgentRunStatus.FAILED.name());
        } finally {
            // 确保清理 ThreadLocal，避免线程池复用时上下文串扰
            AgentContext.clear();
        }
    }

    /**
     * 统一处理 workflow 或 fast-path 的执行结果。
     */
    private void handleWorkflowResult(AgentRun run,
                                      String userId,
                                      String userGoal,
                                      String endpointName,
                                      String modelName,
                                      TodoPlan todoPlan,
                                      WorkflowExecutionResult result) {
        String runId = run.getId();

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

            try {
                String assistantMetaJson = messageService.buildMetaJson(
                        modelName,
                        endpointName,
                        null,
                        null
                );
                messageService.createAssistantMessage(runId, result.getFinalAnswer(), assistantMetaJson);

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
    }

    /**
     * 构建 run 的最终 snapshot JSON，供 DB 持久化和状态查询。
     *
     * <p>snapshot 包含以下信息：
     * <ul>
     *   <li>{@code user_goal} — 用户原始目标</li>
     *   <li>{@code plan} — Todo Plan 详情</li>
     *   <li>{@code completed_items} — 已完成任务列表</li>
     *   <li>{@code citation_map} — 引用来源映射表</li>
     *   <li>{@code answer} / {@code answer_markdown} — 解析后的最终答案</li>
     *   <li>{@code structured_answer} / {@code quality_flags} — 结构化答案与质量标记</li>
     *   <li>{@code context} — 执行上下文</li>
     *   <li>观测数据（由 {@link AgentObservabilityService#attachObservabilityToSnapshot} 附加）</li>
     * </ul>
     *
     * @param userGoal      用户原始目标
     * @param plan          Todo Plan 对象
     * @param completedItems 已完成的任务项
     * @param answer        LLM 输出的原始最终答案文本
     * @param context       执行上下文
     * @param citationMap   引用来源映射表
     * @param status        最终状态
     * @param runId         Run ID（用于关联观测数据）
     * @return JSON 字符串格式的 snapshot
     */
    private String buildSnapshotJson(String userGoal,
                                     Object plan,
                                     Object completedItems,
                                     String answer,
                                     Object context,
                                     AgentCitationService.CitationMap citationMap,
                                     AgentRunStatus status,
                                     String runId) {
        Map<String, Object> snapshot = new HashMap<>();
        // 空保护：若引用表为 null，使用空引用表
        AgentCitationService.CitationMap safeCitationMap = citationMap == null ? AgentCitationService.CitationMap.empty() : citationMap;
        // 解析最终答案：拆分 Markdown 展示文本、结构化答案（JSON）、质量标记
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
            // 将观测数据（LLM traces、工具调用记录等）附加到 snapshot 中
            return observabilityService.attachObservabilityToSnapshot(runId, json, status);
        } catch (Exception e) {
            return observabilityService.attachObservabilityToSnapshot(runId, "{}", status);
        }
    }

    // ======================== 工具方法 ========================

    /** 快速构建 Map 的便捷方法，参数按 key1, val1, key2, val2 顺序传入。 */
    private Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    /** 空安全：null 转为空字符串。 */
    private String nvl(String value) {
        return value == null ? "" : value;
    }

    /**
     * 解析用户目标文本。
     *
     * <p>优先从最新一条用户消息中读取（支持追问场景），
     * 若消息为空则回退到 run ext JSON 中的 userGoal 字段。</p>
     *
     * @param run AgentRun 实体
     * @return 用户目标文本，不为 null
     */
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

    /** 截取字符串前 maxLen 个字符，超出部分用 "..." 表示。 */
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
     * 合并用户指定的 OpenRouter provider 列表与配置中的 validProviders。
     *
     * <p>合并策略：用户指定的 provider 优先排在前面，
     * validProviders 中不重复的追加在后面作为兜底。</p>
     *
     * @param userProviders   用户指定的 provider 列表（可能为空）
     * @param validProviders  配置中该 endpoint 支持的有效 provider 列表
     * @return 合并去重后的 provider 列表
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

    /**
     * 为指定阶段选择最终生效的 {@link StageLlmConfig}。
     *
     * <p>配置优先级（高到低）：</p>
     * <ol>
     *   <li>客户端 run 请求中 {@code stage_config_json.<stageName>} 的字段</li>
     *   <li>顶层 run 请求参数 {@code endpointName / modelName}</li>
     *   <li>本地 agent-llm 配置中对应阶段的 fallback</li>
     * </ol>
     *
     * <p>每个字段独立 fallback：例如客户端只指定了 modelName，则 endpointName
     * 仍可从顶层参数或本地配置中继承。</p>
     *
     * @param requestedEndpointName run 请求顶层 endpointName
     * @param requestedModelName    run 请求顶层 modelName
     * @param fallback               本地 fallback 配置
     * @param extJson                run ext JSON 字符串
     * @param stageName              阶段名（"planning" / "execution" / "final_answer"）
     * @return 合并后的有效 StageLlmConfig，不会为 null
     */
    private StageLlmConfig chooseEffectiveStageConfig(String requestedEndpointName,
                                                      String requestedModelName,
                                                      StageLlmConfig fallback,
                                                      String extJson,
                                                      String stageName) {
        // 从 ext JSON 的 stage_config_json 中提取该阶段的客户端配置
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
        effective.setProviderOrder(clientStage != null && clientStage.getProviderOrder() != null && !clientStage.getProviderOrder().isEmpty()
                ? clientStage.getProviderOrder()
                : (fallback == null ? null : fallback.getProviderOrder()));
        return effective;
    }

    /**
     * 从 run ext JSON 中解析指定阶段的客户端 LLM 配置。
     *
     * <p>ext 中 stage_config_json 可能是 JSON 对象或 JSON 字符串（双重序列化），
     * 本方法兼容两种格式。</p>
     *
     * @param extJson   run ext JSON 字符串
     * @param stageName 阶段名
     * @return 解析后的 StageLlmConfig，若不存在或解析失败则返回 null
     */
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
            // 兼容双重序列化：stage_config_json 可能是字符串
            if (stageNode.isTextual()) {
                stageNode = objectMapper.readTree(stageNode.asText());
            }
            var phaseNode = stageNode.get(stageName);
            if (phaseNode == null || !phaseNode.isObject()) {
                return null;
            }
            StageLlmConfig config = objectMapper.treeToValue(phaseNode, StageLlmConfig.class);
            // 仅当至少有一个有效字段时才返回，避免空配置覆盖 fallback
            return hasAnyStageField(config) ? config : null;
        } catch (Exception e) {
            log.warn("解析 stage_config_json.{} 失败，将使用 run 请求与本地 fallback 合并: {}", stageName, e.getMessage());
            return null;
        }
    }

    /** 返回第一个非空白字符串，若均为空则返回 null。 */
    private String firstNonBlank(String first, String second) {
        return hasText(first) ? first.trim() : (hasText(second) ? second.trim() : null);
    }

    /** 判断字符串是否非 null 且非空白。 */
    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 判断 StageLlmConfig 是否至少有一个有效字段。
     *
     * <p>用于区分"未配置该阶段"和"配置了但全是 null 值"两种情况，
     * 前者允许完全 fallback 到顶层参数。仅当显式配置了至少一个字段时，
     * 该阶段才被视为"存在专用配置"。</p>
     */
    private boolean hasAnyStageField(StageLlmConfig config) {
        return config != null
                && (hasText(config.getEndpointName())
                || hasText(config.getModelName())
                || hasText(config.getReasoningEffort())
                || config.getTemperature() != null
                || config.getMaxTokens() != null
                || (config.getProviderOrder() != null && !config.getProviderOrder().isEmpty()));
    }

    /**
     * 解析阶段的 provider 顺序配置。
     *
     * <p>优先取阶段配置中的 providerOrder，若无则回退到 run 请求级别的 providerOrder。</p>
     */
    private List<String> resolveStageProviderOrder(StageLlmConfig stageConfig, List<String> runProviderOrder) {
        if (stageConfig != null && stageConfig.getProviderOrder() != null && !stageConfig.getProviderOrder().isEmpty()) {
            return stageConfig.getProviderOrder();
        }
        return runProviderOrder;
    }

    /**
     * 解析 Execution 阶段的 OpenRouter reasoning（thinking）配置。
     *
     * <p>推理强度控制模型是否在输出答案前进行内部思考（如 DeepSeek R1、Kimi K2 等模型）。
     * 优先从热加载动态配置读取，其次从静态 application.yml 配置读取。</p>
     *
     * @return reasoning effort 值（如 "high"、"medium"、"low"），或 null 表示不配置
     */
    private String resolveExecutionReasoningEffort() {
        // 1. 尝试从热加载配置读取（支持不重启热更新）
        String effort = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getExecution)
                .map(AgentLlmProperties.Execution::getReasoning)
                .map(AgentLlmProperties.Reasoning::resolveEffort)
                .orElse(null);
        if (effort != null) return effort;

        // 2. 热加载配置不存在，回退到静态配置
        if (llmProperties.getRuntime() != null && llmProperties.getRuntime().getExecution() != null
                && llmProperties.getRuntime().getExecution().getReasoning() != null) {
            return llmProperties.getRuntime().getExecution().getReasoning().resolveEffort();
        }
        return null;
    }
}

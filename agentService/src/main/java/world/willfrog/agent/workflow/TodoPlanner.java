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
import world.willfrog.agent.config.RunStageConfig;
import world.willfrog.agent.config.StageLlmConfig;
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

/**
 * Todo Plan 生成器：把用户自然语言目标转化为可执行的 {@link TodoPlan}。
 *
 * <h3>角色与位置</h3>
 * 本组件由 {@link world.willfrog.agent.service.AgentRunExecutor} 在 Planning 阶段调用，
 * 生成的 Plan 会被传递给 {@link LinearWorkflowExecutor} 或 {@link DagWorkflowExecutor}
 * 进行实际执行。Planner 还要为 Plan 选择 {@link PlanExecutionMode}（LINEAR / DAG / AUTO），
 * 影响后续执行器的选择。
 *
 * <h3>两种 Planning 流程</h3>
 * Planner 根据配置 {@code strategy_stage_enabled} 切换：
 * <ol>
 *   <li><b>新版两阶段结构化输出</b>（{@link #generatePlanWithTwoStageStructured}）：
 *     <ul>
 *       <li>Step 1（{@code planning_strategy}）：让 LLM 输出 overallPlan（含 mode、detail），结构化 JSON。</li>
 *       <li>Step 2（{@code planning_todos}）：基于 overallPlan 进一步输出 todo list，结构化 JSON。</li>
 *       <li>两步共享一个 {@link ReactConversationContext}，最大化 LLM provider 的 KV 缓存命中率。</li>
 *     </ul>
 *   </li>
 *   <li><b>旧版两阶段</b>（{@link #generatePlanWithLegacyTwoStage}）：
 *     <ul>
 *       <li>Step 1（{@code todo_planning_analysis}）：自然语言分析，让 LLM 先"想"。</li>
 *       <li>Step 2（{@code todo_planning}）：基于分析输出结构化 todo list JSON。</li>
 *       <li>保留用于向后兼容。</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h3>容错与重试</h3>
 * 结构化输出可能失败（schema 不合规、引用未知工具等），Planner 内部最多重试 {@code maxAttempts} 次。
 * 失败会通过 {@link AgentObservabilityService} 记录错误类别便于线下分析。
 *
 * <h3>maxTodos 校验</h3>
 * 客户端可在 run 请求里覆盖 {@code maxTodos}，但服务端会用 {@code maxTodosClientCap} 强校验，
 * 超过 cap 直接抛 {@link IllegalArgumentException}，防止恶意刷成本。
 *
 * @see LinearWorkflowExecutor
 * @see DagWorkflowExecutor
 * @see StructuredPlanningSupport
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TodoPlanner {

    /** 提示词服务，提供 reactSystemPrompt 与各阶段 stage instruction */
    private final AgentPromptService promptService;
    /** 事件流服务，发送 PLANNING_* 系列事件 */
    private final AgentEventService eventService;
    /** Run 级 Redis 状态缓存：读已有 plan（用户 plan override 场景）、写最新 plan */
    private final AgentRunStateStore stateStore;
    /** LLM 请求快照构造器，用于观测记录（保留完整 ChatCompletions 入参） */
    private final AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    /** 观测数据服务，记录 LLM 调用、Planning 重试次数、错误类别 */
    private final AgentObservabilityService observabilityService;
    /** 本地 agent-llm 配置热加载器（无需重启即可调整 maxTodos 等参数） */
    private final AgentLlmLocalConfigLoader localConfigLoader;
    /** 静态 agent-llm 配置 bean */
    private final AgentLlmProperties llmProperties;
    /** 历史消息服务，用于多轮对话场景下加载对话历史 */
    private final AgentMessageService messageService;
    /** 上下文压缩器，把过长的对话历史压缩成可塞进 prompt 的摘要 */
    private final AgentContextCompressor contextCompressor;
    /** JSON 工具：解析 LLM 结构化输出、序列化 Plan 持久化 */
    private final ObjectMapper objectMapper;

    /** maxTodos 默认值（默认 10），可被本地配置或 application.yml 覆盖 */
    @Value("${agent.flow.workflow.max-todos:10}")
    private int defaultMaxTodos;

    /**
     * 入口方法：为本次 run 生成最终的 {@link TodoPlan}。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>检查 plan override 状态和已有 plan：
     *     <ul>
     *       <li>若 stateStore 中已存在 plan（如用户主动覆盖），优先解析使用。</li>
     *       <li>否则若 run 实体上有 planJson，解析使用。</li>
     *       <li>都没有则调用 {@link #generatePlan} 让 LLM 生成。</li>
     *     </ul>
     *   </li>
     *   <li>归一化（{@link #normalize}）：补齐 id、sequence，截断到 maxTodos 上限。</li>
     *   <li>设置执行模式（来自 PlanRequest，默认 AUTO）。</li>
     *   <li>持久化到 stateStore，清掉 plan override 标志。</li>
     *   <li>发送 TODO_LIST_CREATED 和 PLANNING_COMPLETED 事件。</li>
     * </ol>
     *
     * <p>任何异常都会发送 PLANNING_FAILED 事件并抛出 {@link IllegalStateException}。</p>
     *
     * @param request planning 请求
     * @return 归一化后的 TodoPlan
     * @throws IllegalStateException Plan 为空或生成失败
     */
    public TodoPlan plan(PlanRequest request) {
        AgentRun run = request.getRun();
        String runId = run.getId();
        String userId = request.getUserId();

        eventService.append(runId, userId, "PLANNING_STARTED", Map.of("run_id", runId));

        try {
            // 1. 检查是否存在用户/客户端提供的覆盖 plan
            boolean override = stateStore.isPlanOverride(runId);
            TodoPlan todoPlan = null;
            Optional<String> stateStoredPlan = stateStore.loadPlan(runId);
            if (stateStoredPlan.isPresent()) {
                todoPlan = parsePlan(stateStoredPlan.get());
            } else if (run.getPlanJson() != null && !run.getPlanJson().isBlank() && !"{}".equals(run.getPlanJson().trim())) {
                // run 实体上的 planJson（旧版调用方使用的字段）
                todoPlan = parsePlan(run.getPlanJson());
            }

            // 收集工具白名单，传递给 LLM（避免 LLM 引用不存在的工具）
            Set<String> toolWhitelist = request.getToolSpecifications().stream()
                    .map(ToolSpecification::name)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            // 2. 没有现成 plan 则让 LLM 生成
            if (todoPlan == null || todoPlan.getItems().isEmpty()) {
                todoPlan = generatePlan(request, toolWhitelist);
            }

            // 3. 归一化：补齐字段、应用 maxTodos 上限
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

            // 4. 持久化最终 plan，清理 override 标志
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

    /**
     * 选择并调用对应的 Plan 生成策略（新版两阶段 vs 旧版两阶段）。
     *
     * @param request       planning 请求
     * @param toolWhitelist 工具白名单
     * @return 生成的 Plan
     */
    private TodoPlan generatePlan(PlanRequest request, Set<String> toolWhitelist) {
        return generatePlanWithRetries(request, toolWhitelist);
    }

    /**
     * 根据 strategyStageEnabled 配置选择新版/旧版两阶段流程。
     *
     * <p>新版（默认）使用纯结构化输出；旧版第一阶段是自然语言、第二阶段才是结构化。</p>
     */
    private TodoPlan generatePlanWithRetries(PlanRequest request, Set<String> toolWhitelist) {
        // 判断是否使用新的两阶段结构化输出
        if (strategyStageEnabled()) {
            return generatePlanWithTwoStageStructured(request, toolWhitelist);
        }
        // 否则使用旧的两阶段流程（第一阶段自然语言）
        return generatePlanWithLegacyTwoStage(request, toolWhitelist);
    }

    /**
     * 新的两阶段结构化输出规划。
     * 第一阶段：统筹规划（结构化输出 overallPlan）
     * 第二阶段：任务拆解（结构化输出 todo list）
     *
     * <p>实现细节：</p>
     * <ul>
     *   <li>两个阶段共享一个 {@link ReactConversationContext}，按 Step1 user → Step1 assistant
     *       → Step2 user → Step2 assistant 顺序累积消息，复用前缀缓存。</li>
     *   <li>每个阶段会临时设置 {@link AgentContext.StructuredOutputSpec}，通过 ChatModel 包装层
     *       传递给 provider 的 structured-output 协议（如 OpenAI JSON Schema）。</li>
     *   <li>多轮重试：单轮抛出 {@link StructuredPlanningSupport.StructuredPlanningException} 时
     *       记录错误类别，再开新的 ctx 重试，直到 maxAttempts 用尽。</li>
     *   <li>finally 中复原 AgentContext 的 stage / structured spec / reasoningEffort，
     *       避免污染后续 ReAct 阶段。</li>
     * </ul>
     *
     * @param request       planning 请求
     * @param toolWhitelist 工具白名单
     * @return 解析后的 Plan；耗尽重试且配置允许 silent fail 时返回空 Plan
     */
    private TodoPlan generatePlanWithTwoStageStructured(PlanRequest request, Set<String> toolWhitelist) {
        String runId = request.getRun().getId();
        boolean structuredEnabled = planningStructuredEnabled();
        int maxAttempts = resolvePlanningMaxAttempts();
        int maxTodos = resolveMaxTodos(request);
        int maxDetailLength = resolveStrategyMaxDetailLength();
        String lastCategory = "";
        String lastError = "";
        observabilityService.markPlanningStructured(runId, structuredEnabled);
        observabilityService.setLastPlanningErrorCategory(runId, "");

        // 准备静态资源（每轮都一样，提前算好）
        String toolList = toolWhitelist.stream().sorted().collect(Collectors.joining(", "));
        String reactSystem = promptService.reactSystemPrompt();
        String dynamicPrefix = promptService.dynamicContextPrefix();
        String dialogueContext = buildDialogueContext(runId, request.getUserGoal());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            observabilityService.incrementPlanningAttempts(runId, false);

            // ─── ReAct 累积式上下文：两步规划共享同一 context ───
            ReactConversationContext ctx = new ReactConversationContext();
            ctx.setSystemMessage(reactSystem);

            // ── Step 1：统筹规划阶段（结构化输出）──
            String strategyStage = promptService.planningStrategyStageInstruction(toolList, maxTodos, maxDetailLength);
            String strategyContent;
            if (dialogueContext.isBlank()) {
                strategyContent = dynamicPrefix + "\n" + strategyStage + "\n\n用户需求：" + request.getUserGoal();
            } else {
                // 多轮对话场景：先注入压缩后的历史对话，再说明当前轮次需求
                strategyContent = dynamicPrefix + "\n" + strategyStage + "\n\n"
                        + "历史对话压缩内容：\n" + dialogueContext
                        + "\n\n当前轮次用户需求：" + request.getUserGoal();
            }
            ctx.addUserMessage(strategyContent);

            // 保存原 AgentContext，finally 中恢复
            String previousStage = AgentContext.getStage();
            AgentContext.StructuredOutputSpec previousSpec = AgentContext.getStructuredOutputSpec();
            AgentContext.setPhase(AgentObservabilityService.PHASE_PLANNING);
            AgentContext.setStage("planning_strategy");

            // 设置 reasoning 配置（让 OpenRouter 等支持 thinking 的 provider 使用相应预算）
            setPlanningReasoningEffort();

            ChatResponse strategyResponse;
            String strategyRaw;
            StructuredPlanningSupport.OverallPlan overallPlan = null;

            try {
                // 第一阶段使用结构化输出
                if (structuredEnabled) {
                    AgentContext.setStructuredOutputSpec(new AgentContext.StructuredOutputSpec(
                            "strategy_plan",
                            planningStructuredStrict(),
                            StructuredPlanningSupport.strategyStageJsonSchema(maxDetailLength),
                            planningRequireProviderParameters(),
                            planningAllowProviderFallbacks()
                    ));
                } else {
                    AgentContext.clearStructuredOutputSpec();
                }

                // Step 1 LLM call
                long strategyStartedAt = System.currentTimeMillis();
                strategyResponse = request.getModel().chat(ctx.getMessages());
                strategyRaw = strategyResponse.aiMessage() == null ? "" : nvl(strategyResponse.aiMessage().text());
                long strategyCompletedAt = System.currentTimeMillis();

                // 记录 LLM 调用观测
                Map<String, Object> strategySnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                        request.getEndpointName(),
                        request.getEndpointBaseUrl(),
                        request.getModelName(),
                        ctx.getMessages(),
                        request.getToolSpecifications(),
                        Map.of("stage", "planning_strategy", "attempt", attempt)
                );
                observabilityService.recordLlmCall(
                        runId,
                        AgentObservabilityService.PHASE_PLANNING,
                        strategyResponse.metadata() != null ? strategyResponse.metadata().tokenUsage() : null,
                        strategyCompletedAt - strategyStartedAt,
                        strategyStartedAt,
                        strategyCompletedAt,
                        request.getEndpointName(),
                        request.getModelName(),
                        null,
                        strategySnapshot,
                        strategyRaw
                );

                // 解析第一阶段输出（必须满足 strategyStageJsonSchema 的约束）
                JsonNode strategyRoot = StructuredPlanningSupport.parseStructuredJson(objectMapper, strategyRaw);
                StructuredPlanningSupport.ValidationResultWithData<StructuredPlanningSupport.OverallPlan> strategyValidation =
                        StructuredPlanningSupport.validateStrategyStage(strategyRoot, maxDetailLength);
                if (!strategyValidation.valid()) {
                    throw new StructuredPlanningSupport.StructuredPlanningException(
                            strategyValidation.category(), strategyValidation.message());
                }
                overallPlan = strategyValidation.data();

                // Step1 结果回写到 ctx，作为 Step2 的 assistant 历史
                ctx.addAssistantMessage(strategyRaw);

                // ── Step 2：任务拆解阶段 ──
                String todosStage = promptService.planningTodosStageInstruction(overallPlan, toolList, maxTodos);
                ctx.addUserMessage(todosStage);

                AgentContext.setStage("planning_todos");
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

                // Step 2 LLM call
                long todosStartedAt = System.currentTimeMillis();
                ChatResponse todosResponse = request.getModel().chat(ctx.getMessages());
                String todosRaw = todosResponse.aiMessage() == null ? "" : nvl(todosResponse.aiMessage().text());
                Map<String, Object> todosSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                        request.getEndpointName(),
                        request.getEndpointBaseUrl(),
                        request.getModelName(),
                        ctx.getMessages(),
                        request.getToolSpecifications(),
                        Map.of("stage", "planning_todos", "attempt", attempt)
                );
                long todosCompletedAt = System.currentTimeMillis();
                observabilityService.recordLlmCall(
                        runId,
                        AgentObservabilityService.PHASE_PLANNING,
                        todosResponse.metadata() != null ? todosResponse.metadata().tokenUsage() : null,
                        todosCompletedAt - todosStartedAt,
                        todosStartedAt,
                        todosCompletedAt,
                        request.getEndpointName(),
                        request.getModelName(),
                        null,
                        todosSnapshot,
                        todosRaw
                );

                // 解析第二阶段输出（包含 todo list + dependsOn + 工具白名单校验）
                JsonNode todosRoot = StructuredPlanningSupport.parseStructuredJson(objectMapper, todosRaw);
                StructuredPlanningSupport.ValidationResult todosValidation =
                        StructuredPlanningSupport.validateTodoPlan(todosRoot, maxTodos, toolWhitelist);
                if (!todosValidation.valid()) {
                    throw new StructuredPlanningSupport.StructuredPlanningException(
                            todosValidation.category(), todosValidation.message());
                }

                // 解析成功：构造 TodoPlan，把 Step1 的 detail/mode 注入到 plan 元信息中
                TodoPlan todoPlan = parsePlanNode(todosRoot);
                todoPlan.setAnalysis(overallPlan.detail());
                todoPlan.setExecutionMode(parsePlanExecutionMode(overallPlan.mode()));
                observabilityService.setLastPlanningErrorCategory(runId, "");
                return todoPlan;

            } catch (StructuredPlanningSupport.StructuredPlanningException e) {
                // 结构化解析失败：记录类别供下一轮重试参考，触发 PLANNING_RETRY 事件
                lastCategory = nvl(e.category());
                lastError = nvl(e.getMessage());
                observabilityService.setLastPlanningErrorCategory(runId, lastCategory);
                eventService.append(runId, request.getUserId(), "PLANNING_RETRY", Map.of(
                        "attempt", attempt,
                        "max_attempts", maxAttempts,
                        "error_category", nvl(lastCategory),
                        "error", nvl(lastError)
                ));
            } finally {
                // 复原 AgentContext，避免污染后续 ReAct 流程
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
        }

        // 重试耗尽：根据配置决定抛错还是返回空 plan
        if (planningFailOnExhaustedRetries()) {
            throw new IllegalStateException("planning_retry_exhausted:"
                    + nvl(lastCategory) + ":" + nvl(lastError));
        }
        return TodoPlan.builder().analysis("").items(List.of()).build();
    }

    /**
     * 旧的两阶段规划（第一阶段自然语言，第二阶段结构化）。
     * 保留用于向后兼容。
     *
     * <p>与新版差异：</p>
     * <ul>
     *   <li>Step1（{@code todo_planning_analysis}）：用自然语言让 LLM 输出"规划思路"，
     *       不强约束 JSON 格式，更利于 LLM 自由推理。</li>
     *   <li>Step2（{@code todo_planning}）：基于自然语言分析转出结构化 todo list。</li>
     *   <li>DAG 模式提示：仅在 executionMode 为 AUTO/DAG 时，向 Step1 注入 dagModeGuidancePrompt，
     *       引导 LLM 输出可并行的拆解方案。</li>
     * </ul>
     *
     * @param request       planning 请求
     * @param toolWhitelist 工具白名单
     * @return 解析后的 Plan
     */
    private TodoPlan generatePlanWithLegacyTwoStage(PlanRequest request, Set<String> toolWhitelist) {
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
        // 仅 AUTO/DAG 模式注入 DAG 引导提示；显式 LINEAR 则不注入，避免误导 LLM
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
            // 分析阶段不需要结构化输出，清掉之前的 spec
            AgentContext.clearStructuredOutputSpec();

            setPlanningReasoningEffort();

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
                // 注意：finally 在 LLM 调用结束就执行（即使解析阶段还未开始），
                // 确保 AgentContext 在解析失败时也能被复原
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

            // 解析阶段在 finally 外执行：若失败，下一轮重试用新的 ctx 重新构造
            try {
                JsonNode root = StructuredPlanningSupport.parseStructuredJson(objectMapper, raw);
                StructuredPlanningSupport.ValidationResult validation = StructuredPlanningSupport.validateTodoPlan(root, maxTodos, toolWhitelist);
                if (!validation.valid()) {
                    throw new StructuredPlanningSupport.StructuredPlanningException(validation.category(), validation.message());
                }
                TodoPlan todoPlan = parsePlanNode(root);
                // 把自然语言分析记到 plan.analysis 字段，供观测/前端展示
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

    /**
     * 设置 Planning 阶段的 reasoning effort 配置。
     *
     * <p>优先级：客户端 run 请求中 stage_config_json 的 planning 段 &gt; 本地热加载 &gt; 静态配置。
     * reasoning effort 会传递给支持 thinking budget 的 provider（如 OpenRouter / Claude）。</p>
     */
    private void setPlanningReasoningEffort() {
        String planningReasoningEffort = null;
        RunStageConfig stageConfig = AgentContext.getStageConfig();
        if (stageConfig != null && stageConfig.getPlanning() != null
                && stageConfig.getPlanning().getReasoningEffort() != null) {
            planningReasoningEffort = stageConfig.getPlanning().getReasoningEffort();
        }
        if (planningReasoningEffort == null) {
            planningReasoningEffort = resolvePlanningReasoningEffort();
        }
        if (planningReasoningEffort != null) {
            AgentContext.setReasoningEffort(planningReasoningEffort);
        }
    }

    /**
     * 归一化 Plan：补齐字段、应用 maxTodos 上限。
     *
     * <p>处理细节：</p>
     * <ul>
     *   <li>每个 item 重新分配 sequence（从 1 开始），保证连续。</li>
     *   <li>id 缺失时按 "todo_{seq}" 自动生成。</li>
     *   <li>超出 maxTodos 的 item 被截断丢弃。</li>
     *   <li>所有 item 状态强制设为 PENDING，createdAt 设为当前时间。</li>
     * </ul>
     *
     * @param source        LLM 输出解析得到的原始 Plan
     * @param maxTodos      Plan 中允许的最大 todo 数量
     * @param toolWhitelist 工具白名单（当前未在此方法使用，保留以备扩展）
     * @return 归一化后的新 TodoPlan
     */
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

    /**
     * 解析持久化的 plan JSON 字符串为 {@link TodoPlan}。
     *
     * <p>用于 plan override 场景：客户端上传的 plan 字符串或 stateStore 中的历史 plan。
     * 解析失败时返回空 plan（不抛异常），让调用方继续走 LLM 生成流程。</p>
     *
     * @param planJson plan 的 JSON 字符串
     * @return 解析后的 plan；解析失败时为空 plan
     */
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

    /**
     * 从 JSON 节点解析为 {@link TodoPlan}，处理多种字段命名兼容。
     *
     * <p>兼容情况：</p>
     * <ul>
     *   <li>{@code items} 或 {@code todos} 都接受作为 todo 列表字段（LLM 输出常变）。</li>
     *   <li>{@code extractedEntities} 或 {@code extracted_entities} 都接受（驼峰/下划线兼容）。</li>
     *   <li>每个 item 的 {@code dependsOn} 数组解析为 {@code List<String>}，支持 DAG 模式。</li>
     *   <li>extractedEntities 自动去重，便于后续工具检索（指数代码、股票名等）。</li>
     * </ul>
     *
     * @param root JSON 根节点
     * @return 解析后的 plan
     */
    private TodoPlan parsePlanNode(JsonNode root) {
        if (root == null || !root.isObject()) {
            return TodoPlan.builder().analysis("").items(List.of()).build();
        }
        // items 与 todos 二者择其一（LLM 输出风格变化时的兼容）
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

        // 解析 extractedEntities（去重保序）
        List<String> extractedEntities = new ArrayList<>();
        JsonNode entitiesNode = root.path("extractedEntities");
        if (!entitiesNode.isArray()) {
            entitiesNode = root.path("extracted_entities");
        }
        if (entitiesNode.isArray()) {
            for (JsonNode entityNode : entitiesNode) {
                String entity = nvl(entityNode.asText("")).trim();
                if (!entity.isBlank() && !extractedEntities.contains(entity)) {
                    extractedEntities.add(entity);
                }
            }
        }

        return TodoPlan.builder()
                .analysis(nvl(root.path("analysis").asText("")))
                .items(items)
                .extractedEntities(extractedEntities)
                .build();
    }

    /**
     * 将 JsonNode 转为 Map（保留以备 metadata 解析使用）。
     *
     * <p>仅对 object 类型节点有效，其它类型返回空 Map。</p>
     */
    private Map<String, Object> toMap(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * 解析 TodoType 枚举（容错：无法识别时退回 TOOL_CALL）。
     */
    private TodoType parseType(String text) {
        try {
            return TodoType.valueOf(nvl(text).trim().toUpperCase());
        } catch (Exception e) {
            return TodoType.TOOL_CALL;
        }
    }

    /**
     * 解析 ExecutionMode（容错：无法识别时退回 AUTO）。
     */
    private ExecutionMode parseExecutionMode(String text) {
        try {
            return ExecutionMode.valueOf(nvl(text).trim().toUpperCase());
        } catch (Exception e) {
            return ExecutionMode.AUTO;
        }
    }

    /**
     * 解析 PlanExecutionMode（容错：无法识别时退回 AUTO）。
     *
     * <p>用于把 Step1 的 overallPlan.mode 字符串转为枚举，再赋给 plan。</p>
     */
    private PlanExecutionMode parsePlanExecutionMode(String text) {
        try {
            return PlanExecutionMode.valueOf(nvl(text).trim().toUpperCase());
        } catch (Exception e) {
            return PlanExecutionMode.AUTO;
        }
    }

    /**
     * 解析本次 run 适用的 maxTodos（todo 上限）。
     *
     * <p>优先级：</p>
     * <ol>
     *   <li>客户端 PlanRequest 中传入的 maxTodos（需通过 cap 校验，超 cap 时抛异常）。</li>
     *   <li>本地热加载配置 runtime.planning.maxTodos。</li>
     *   <li>静态 application.yml 配置 runtime.planning.maxTodos。</li>
     *   <li>本类默认值 {@link #defaultMaxTodos}。</li>
     * </ol>
     *
     * <p>所有路径最终都会被 clamp 到 [1, 50] 区间，避免极端值。</p>
     *
     * @param request planning 请求
     * @return 该 run 的 todo 上限
     * @throws IllegalArgumentException 客户端 maxTodos 超过服务端 cap
     */
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

    /**
     * 解析服务端为客户端 maxTodos 设置的上限（cap）。
     *
     * <p>用于防止客户端恶意/错误地传入过大 maxTodos 导致成本失控。</p>
     */
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

    /**
     * 判断 Planning 阶段是否启用结构化输出。
     *
     * <p>默认 true（启用），允许通过配置关闭。</p>
     */
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

    /**
     * 是否启用第一阶段结构化输出（统筹规划阶段）。
     * 默认 true（使用新的两阶段结构化输出）。
     */
    private boolean strategyStageEnabled() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrategyStageEnabled);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrategyStageEnabled)
                .orElse(null);
        // 默认启用新的两阶段结构化输出
        return base == null || base;
    }

    /**
     * 解析第一阶段 detail 的最大长度限制。
     * 默认 500 字符。
     *
     * <p>用于约束 LLM 输出的 strategy.detail 字段长度，过长的分析会被 schema 校验拒绝并触发重试。</p>
     */
    private int resolveStrategyMaxDetailLength() {
        int local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrategyMaxDetailLength)
                .orElse(0);
        if (local > 0) {
            return local;
        }
        int base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrategyMaxDetailLength)
                .orElse(0);
        if (base > 0) {
            return base;
        }
        return 500; // 默认值
    }

    /**
     * 解析 Planning 阶段的最大重试次数（默认 3，clamp 到 [1, 10]）。
     */
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

    /**
     * 是否启用 strict structured output（schema 严格匹配）。
     *
     * <p>默认 true：LLM 输出必须严格符合 JSON Schema，避免多余字段；
     * false 时允许 LLM 输出多余字段（schema 校验更宽松）。</p>
     */
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

    /**
     * Planning 重试耗尽时是否抛异常。
     *
     * <p>默认 true：抛错让 run 失败；false 时返回空 plan，run 仍然继续（外部需处理空 plan）。</p>
     */
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

    /**
     * 是否要求 provider 必须支持 structured output 协议（不支持则拒绝降级到自由文本）。
     *
     * <p>默认 true：强制 provider 端解析；false 时 provider 可选择不发送 structured output 头。</p>
     */
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

    /**
     * 是否允许 OpenRouter 等聚合 provider 在主 provider 不支持 structured output 时
     * fallback 到其它 provider。
     *
     * <p>默认 false：禁止 fallback，保证调用稳定性；true 时容忍 provider 切换。</p>
     */
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

    /** 将整数夹到 [min, max] 区间。 */
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 判断当前模式是否应注入 DAG 引导提示。
     *
     * <p>AUTO/DAG/null 均返回 true（让 LLM 看到 DAG 提示，从而可能输出 DAG 风格的 plan）；
     * 仅 LINEAR 返回 false（避免 LLM 在已确定串行的场景下被 DAG 提示干扰）。</p>
     */
    private boolean shouldInjectDagGuidance(PlanExecutionMode mode) {
        if (mode == null) {
            return true;
        }
        return mode == PlanExecutionMode.AUTO || mode == PlanExecutionMode.DAG;
    }

    /**
     * 安全的 JSON 序列化：序列化失败时返回 {@code "{}"}，避免 plan 持久化失败破坏整个 run。
     */
    private String safeWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 空安全：null 转为空字符串。 */
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
     * <p>若没有历史消息（首轮对话）或加载失败，返回空字符串；
     * 调用方据此判断是否注入"历史对话压缩内容"段落到 prompt。</p>
     *
     * @param runId            Run ID
     * @param currentUserGoal  当前轮次用户问题（压缩时作为重点保留）
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

    /**
     * Planning 请求 DTO：封装本次 plan 调用所需的所有参数。
     *
     * <p>构造方通常是 {@link world.willfrog.agent.service.AgentRunExecutor}，
     * 它在完成 run 加载、stage 配置解析、工具注册后，把所有上下文打包成此对象传给 Planner。</p>
     */
    @Data
    @Builder
    public static class PlanRequest {
        /** Agent Run 实体（含 runId、userId、planJson 等） */
        private AgentRun run;
        /** 触发本次 run 的用户 ID */
        private String userId;
        /** 用户原始目标文本（顶层问题） */
        private String userGoal;
        /** Planning 阶段使用的 ChatModel（已根据 stage 配置选定 endpoint、model、temperature 等） */
        private ChatModel model;
        /** 本 run 允许 LLM 调用的工具规范列表 */
        private List<ToolSpecification> toolSpecifications;
        /** ChatModel 对应的 endpoint 名称（供观测记录） */
        private String endpointName;
        /** Endpoint base URL（供观测记录） */
        private String endpointBaseUrl;
        /** ChatModel 对应的 model 名称（供观测记录） */
        private String modelName;
        /** 执行模式（影响 DAG 引导提示是否注入；最终也会写入 plan.executionMode） */
        private PlanExecutionMode executionMode;
        /** 客户端可选覆盖：本次 run 最多规划几个 todo（null 则使用服务端配置）。 */
        private Integer maxTodos;
    }
}

package world.willfrog.agentlangchain.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.service.ReactConversationContext;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.StructuredPlanningSupport;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 规划阶段（planning）的核心类。
 *
 * <h2>在 agent run 生命周期中的位置</h2>
 * 每个 agent run 的第一步都是规划（planning）——把用户输入的自然语言目标拆分为可执行的
 * Todo 列表。本类是 agentLangchainService 中唯一负责这个步骤的组件。
 *
 * <h2>两套规划路径</h2>
 * 实际存在两套规划实现，通过 Nacos 配置项
 * {@code runtime.planning.structuredOutput.strategyStageEnabled} 切换：
 * <ol>
 *   <li><b>两阶段结构化规划（strategy → todos）</b>：先让 LLM 输出一个整体策略
 *       （分析用户意图、判断 LINEAR/DAG 模式），再基于该策略生成具体的 Todo 列表。
 *       每一步都通过 OpenRouter 的 structured output（json_schema）约束输出格式。</li>
 *   <li><b>单阶段 LangChain4j（LC4j）AI Service 规划</b>：通过 LC4j 的
 *       {@code @AiService} 接口代理，直接调用 LLM 生成 Todo 列表。
 *       保留为两阶段规划的 legacy-compatible（兼容旧版）fallback（降级）路径。</li>
 * </ol>
 *
 * <h2>Structured Output 的 ThreadLocal 机制</h2>
 * LLM 调用的实际 HTTP 请求由 {@code OpenRouterProviderRoutedChatModel} 发出。
 * 它<strong>不读取</strong> LangChain4j 的 {@code ChatRequest.responseFormat()}，
 * 而是从 {@link AgentContext} 的 ThreadLocal 中读取
 * {@link AgentContext.StructuredOutputSpec}。因此本类在每次 LLM 调用前通过
 * {@link #applyStructuredSpec} 设置 ThreadLocal，调用结束后在 finally 块中恢复/清理，
 * 避免污染后续执行阶段（execution）的 LLM 调用。
 *
 * <h2>与 legacy agentService 的关系</h2>
 * 本类的两阶段路径直接复用了 legacy 的 {@link StructuredPlanningSupport} 做 schema
 * 生成和 JSON 校验；provider 参数策略由 {@link LangchainPlanningStructuredOutputSettings}
 * 控制——其中 OpenRouter 的 {@code require_parameters} 默认设为 false，
 * 避免因 {@code stream_options} 等扩展字段导致 provider 路由错误。
 *
 * @see LangchainPlanningStructuredOutputSettings
 * @see LangchainPlannerAiService 单阶段路径使用的 LC4j AI Service 接口
 * @see StructuredPlanningSupport 两阶段路径复用的 schema 与校验工具
 * @see AgentContext ThreadLocal 上下文管理
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LangchainAiPlanner {

    /** 两阶段规划中 strategy 阶段的 json_schema 名称，传给 OpenRouter */
    private static final String STRATEGY_SCHEMA_NAME = "overall_plan";
    /** 两阶段规划中 todo 列表阶段的 json_schema 名称，及单阶段规划的 schema 名称 */
    private static final String TODO_PLAN_SCHEMA_NAME = "todo_plan";
    /** 默认最大 Todo 数量，可被 Nacos 配置覆盖 */
    private static final int DEFAULT_MAX_TODOS = 10;
    /** 两阶段规划的最大重试次数（schema 校验失败时重试） */
    private static final int DEFAULT_MAX_ATTEMPTS = 2;

    private final AgentPromptService promptService;
    private final LangchainPlanningStructuredOutputSettings structuredOutputSettings;
    private final ObjectMapper objectMapper;

    /**
     * 执行一次完整的 agent 规划，返回 Todo 计划。
     *
     * <h3>调用链路</h3>
     * 在 agent run 生命周期中，本方法由
     * {@code LangchainLinearRunPipelineImpl.executeRun()} 中调用。
     * 传入的 {@code request} 中已包含 pipeline 层解析好的
     * {@link dev.langchain4j.model.chat.ChatModel ChatModel}（含 endpoint/model/provider 配置）、
     * 可用工具列表（{@code toolSpecifications}）和用户原始输入（{@code userGoal}）。
     *
     * <h3>路径选择</h3>
     * 根据 Nacos 配置 {@code runtime.planning.structuredOutput.strategyStageEnabled}
     * 决定走两阶段（strategy → todos）还是单阶段（LC4j AI Service）。
     *
     * <h3>ThreadLocal 保护</h3>
     * 调用前后保存并恢复 {@link AgentContext} 中的 phase、stage 和 structured output spec，
     * 确保规划阶段的 ThreadLocal 设置不会泄漏到后续的 execution 阶段。
     *
     * @param request 规划请求，含 ChatModel、工具列表、用户目标等
     * @return 包含 analysis、Todo 列表和执行模式的完整计划
     * @throws IllegalArgumentException 请求缺少必要字段
     * @throws IllegalStateException    规划重试耗尽或 LLM 返回空计划
     */
    public LangchainTodoPlan plan(LangchainPlanningRequest request) {
        validate(request);
        int maxTodos = resolveMaxTodos(request.getMaxTodos());
        PlanExecutionMode mode = request.getExecutionMode() == null
                ? PlanExecutionMode.LINEAR
                : request.getExecutionMode();
        String toolList = buildToolList(request.getToolSpecifications());
        Set<String> toolWhitelist = buildToolWhitelist(request.getToolSpecifications());

        AgentContext.setPhase(AgentObservabilityService.PHASE_PLANNING);
        String previousStage = AgentContext.getStage();
        AgentContext.StructuredOutputSpec previousSpec = AgentContext.getStructuredOutputSpec();
        try {
            if (structuredOutputSettings.strategyStageEnabled()) {
                return planTwoStageStructured(request, mode, maxTodos, toolList, toolWhitelist);
            }
            return planSingleStageLegacyTemplate(request, mode, maxTodos, toolList, toolWhitelist);
        } finally {
            restoreStructuredOutputSpec(previousSpec);
            restoreStage(previousStage);
        }
    }

    /**
     * 两阶段结构化规划（strategy → todos）。
     *
     * <h3>为什么需要两个阶段</h3>
     * 直接让 LLM 输出 Todo 列表可能导致以下问题：
     * <ol>
     *   <li>LLM 没想清楚就列 Todo，输出不完整或逻辑矛盾</li>
     *   <li>无法在生成 Todo 前向 LLM 注入执行模式（LINEAR/DAG）的明确指令</li>
     *   <li>多轮对话场景下缺少对历史上下文的整体分析</li>
     * </ol>
     * 两阶段规划让 LLM 先输出一个"整体策略"（分析用户意图、决定执行模式），
     * 再将策略作为提示词的一部分注入第二阶段（Todo 生成），显著提高 Todo 质量。
     *
     * <h3>执行流程</h3>
     * <pre>
     * 1. 构建对话上下文（system prompt + 动态前缀 + 用户目标）
     * 2. Strategy 阶段：LLM 输出 OverallPlan（含 mode、detail）
     * 3. 校验 Strategy JSON（通过 StructuredPlanningSupport）
     * 4. Todos 阶段：将 strategy 作为 assistant 消息注入，LLM 输出 Todo 列表
     * 5. 校验 Todo JSON（schema + toolWhitelist）
     * 6. 成功返回；失败则重试（最多 maxAttempts 次）
     * </pre>
     *
     * <h3>Structured Output 注入</h3>
     * 每个 LLM 调用前调用 {@link #applyStructuredSpec}，将 json_schema
     * 写入 {@link AgentContext} ThreadLocal，使
     * {@code OpenRouterProviderRoutedChatModel} 能正确注入
     * {@code response_format} 和 {@code provider} 参数。
     *
     * @see StructuredPlanningSupport#strategyStageJsonSchema(int)
     * @see LangchainPlanningStructuredOutputSettings#todoPlanningJsonSchema()
     */
    private LangchainTodoPlan planTwoStageStructured(LangchainPlanningRequest request,
                                                     PlanExecutionMode mode,
                                                     int maxTodos,
                                                     String toolList,
                                                     Set<String> toolWhitelist) {
        int maxAttempts = resolvePlanningMaxAttempts();
        int maxDetailLength = structuredOutputSettings.strategyMaxDetailLength();
        boolean structuredEnabled = structuredOutputSettings.structuredEnabled();
        String dialogueContext = nvl(request.getDialogueContext());
        String reactSystem = promptService.reactSystemPrompt();
        String dynamicPrefix = promptService.dynamicContextPrefix();
        StructuredPlanningSupport.StructuredPlanningException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ReactConversationContext ctx = new ReactConversationContext();
            ctx.setSystemMessage(reactSystem);
            try {
                String strategyStage = promptService.planningStrategyStageInstruction(toolList, maxTodos, maxDetailLength);
                String strategyContent = dialogueContext.isBlank()
                        ? dynamicPrefix + "\n" + strategyStage + "\n\n用户需求：" + request.getUserGoal()
                        : dynamicPrefix + "\n" + strategyStage + "\n\n历史对话压缩内容：\n" + dialogueContext
                        + "\n\n当前轮次用户需求：" + request.getUserGoal();
                ctx.addUserMessage(strategyContent);

                AgentContext.setStage("planning_strategy");
                applyStructuredSpec(
                        structuredEnabled,
                        STRATEGY_SCHEMA_NAME,
                        structuredOutputSettings.structuredStrict(),
                        StructuredPlanningSupport.strategyStageJsonSchema(maxDetailLength),
                        request
                );
                ChatResponse strategyResponse = request.getModel().chat(ctx.getMessages());
                String strategyRaw = strategyResponse.aiMessage() == null ? "" : nvl(strategyResponse.aiMessage().text());
                JsonNode strategyRoot = StructuredPlanningSupport.parseStructuredJson(objectMapper, strategyRaw);
                StructuredPlanningSupport.ValidationResultWithData<StructuredPlanningSupport.OverallPlan> strategyValidation =
                        StructuredPlanningSupport.validateStrategyStage(strategyRoot, maxDetailLength);
                if (!strategyValidation.valid()) {
                    throw new StructuredPlanningSupport.StructuredPlanningException(
                            strategyValidation.category(), strategyValidation.message());
                }
                StructuredPlanningSupport.OverallPlan overallPlan = strategyValidation.data();
                ctx.addAssistantMessage(strategyRaw);

                String todosStage = promptService.planningTodosStageInstruction(
                        overallPlan.mode(), overallPlan.detail(), toolList, maxTodos);
                ctx.addUserMessage(todosStage);

                AgentContext.setStage("planning_todos");
                applyStructuredSpec(
                        structuredEnabled,
                        TODO_PLAN_SCHEMA_NAME,
                        structuredOutputSettings.structuredStrict(),
                        structuredOutputSettings.todoPlanningJsonSchema(),
                        request
                );
                ChatResponse todosResponse = request.getModel().chat(ctx.getMessages());
                String todosRaw = todosResponse.aiMessage() == null ? "" : nvl(todosResponse.aiMessage().text());
                JsonNode todosRoot = StructuredPlanningSupport.parseStructuredJson(objectMapper, todosRaw);
                StructuredPlanningSupport.ValidationResult todosValidation =
                        StructuredPlanningSupport.validateTodoPlan(todosRoot, maxTodos, toolWhitelist);
                if (!todosValidation.valid()) {
                    throw new StructuredPlanningSupport.StructuredPlanningException(
                            todosValidation.category(), todosValidation.message());
                }
                LangchainTodoPlan plan = LangchainTodoPlanParser.fromJsonRoot(todosRoot, mode, maxTodos);
                if (overallPlan.detail() != null && !overallPlan.detail().isBlank()) {
                    plan = LangchainTodoPlan.builder()
                            .analysis(overallPlan.detail())
                            .items(plan.getItems())
                            .extractedEntities(plan.getExtractedEntities())
                            .executionMode(plan.getExecutionMode())
                            .build();
                }
                log.info(
                        "[LangchainAiPlanner] runId={} two_stage_planning ok attempt={} todos={}",
                        nvl(request.getRunId()),
                        attempt,
                        plan.getItems() == null ? 0 : plan.getItems().size()
                );
                return plan;
            } catch (StructuredPlanningSupport.StructuredPlanningException e) {
                lastError = e;
                log.warn(
                        "[LangchainAiPlanner] runId={} planning attempt {} failed: {} {}",
                        nvl(request.getRunId()),
                        attempt,
                        e.category(),
                        e.getMessage()
                );
            } finally {
                AgentContext.clearStructuredOutputSpec();
            }
        }
        throw new IllegalStateException("planning_retry_exhausted:"
                + (lastError == null ? "unknown" : lastError.category() + ":" + lastError.getMessage()));
    }

    /**
     * 单阶段规划：通过 LangChain4j 的 {@code @AiService} 接口代理直接生成 Todo 列表。
     *
     * <h3>与两阶段路径的关键差异</h3>
     * <ol>
     *   <li>不先问 strategy，直接把动态前缀 + Todo 阶段指令 + 用户目标拼成一条消息发给 LLM</li>
     *   <li>使用 LC4j 的 {@code AiServices.builder()} 创建代理，
     *       LLM 返回的 JSON 通过 {@link LangchainTodoPlanResponse} POJO 自动反序列化</li>
     *   <li>不走 {@link StructuredPlanningSupport} 的校验逻辑，
     *       而是通过 {@link #normalizeResponse} 对 POJO 字段做规范化处理</li>
     * </ol>
     *
     * <h3>为什么 LC4j POJO 返回也需要 ThreadLocal</h3>
     * LC4j 的 {@code @AiService} 在检测到 POJO 返回类型时，会自动向
     * {@code ChatRequest} 注入 {@code response_format = json_schema}。
     * 但 {@code OpenRouterProviderRoutedChatModel} 不读
     * {@code ChatRequest.responseFormat()}，只读 ThreadLocal 中的
     * {@link AgentContext.StructuredOutputSpec}。
     * 因此仍需在调用前显式设置 structured output spec。
     *
     * <h3>systemMessageProvider 的作用</h3>
     * {@code systemMessageProvider(ignored -> promptService.reactSystemPrompt())}
     * 让 LC4j 在每次调用时动态生成 system prompt，而不是在构建时固化。
     * 这确保了日期等动态系统提示内容每次都是最新的。
     * （工具列表在 user message 的 planning stage instruction 中单独注入，
     * 不走 systemMessageProvider。）
     *
     * @see LangchainPlannerAiService 规划专用的 LC4j AI Service 接口
     * @see LangchainTodoPlanResponse 自动反序列化的响应 POJO
     */
    private LangchainTodoPlan planSingleStageLegacyTemplate(LangchainPlanningRequest request,
                                                            PlanExecutionMode mode,
                                                            int maxTodos,
                                                            String toolList,
                                                            Set<String> toolWhitelist) {
        LangchainPlannerAiService service = dev.langchain4j.service.AiServices.builder(LangchainPlannerAiService.class)
                .chatModel(request.getModel())
                .systemMessageProvider(ignored -> promptService.reactSystemPrompt())
                .build();
        AgentContext.setStage("planning_todos");
        boolean structuredEnabled = structuredOutputSettings.structuredEnabled();
        try {
            if (structuredEnabled) {
                AgentContext.setStructuredOutputSpec(new AgentContext.StructuredOutputSpec(
                        TODO_PLAN_SCHEMA_NAME,
                        structuredOutputSettings.structuredStrict(),
                        structuredOutputSettings.todoPlanningJsonSchema(),
                        structuredOutputSettings.requireProviderParameters(request.getPlanningEndpointName()),
                        structuredOutputSettings.allowProviderFallbacks()
                ));
            }
            String dialogueCtx = nvl(request.getDialogueContext());
            String userMessage;
            if (dialogueCtx.isBlank()) {
                userMessage = promptService.dynamicContextPrefix() + "\n"
                        + promptService.planningTodosStageInstruction(mode.name(), "", toolList, maxTodos)
                        + "\n\n用户需求：" + request.getUserGoal();
            } else {
                userMessage = promptService.dynamicContextPrefix() + "\n"
                        + promptService.planningTodosStageInstruction(mode.name(), "", toolList, maxTodos)
                        + "\n\n历史对话压缩内容：\n" + dialogueCtx
                        + "\n\n当前轮次用户需求：" + request.getUserGoal();
            }
            LangchainTodoPlanResponse response = service.plan(userMessage);
            return normalizeResponse(response, maxTodos, mode);
        } finally {
            AgentContext.clearStructuredOutputSpec();
        }
    }

    /**
     * 向 {@link AgentContext} ThreadLocal 写入 structured output 配置。
     *
     * <p>这是连接 langchain planner 和 shared 层
     * {@code OpenRouterProviderRoutedChatModel} 的关键桥梁。
     * 后者不读取 LC4j 的 {@code ChatRequest.responseFormat()}，
     * 只从 ThreadLocal 读取 {@link AgentContext.StructuredOutputSpec}。
     *
     * @param structuredEnabled 是否启用（false 则清理 ThreadLocal）
     * @param schemaName        OpenRouter json_schema 的 name 字段（如 "todo_plan"）
     * @param strict            是否开启 strict 模式（强约束 JSON 输出格式）
     * @param schema            JSON Schema 定义（Map 结构）
     * @param request           用于提取 planning endpoint 名，决定 provider 参数策略
     */
    private void applyStructuredSpec(boolean structuredEnabled,
                                     String schemaName,
                                     boolean strict,
                                     java.util.Map<String, Object> schema,
                                     LangchainPlanningRequest request) {
        if (!structuredEnabled) {
            AgentContext.clearStructuredOutputSpec();
            return;
        }
        AgentContext.setStructuredOutputSpec(new AgentContext.StructuredOutputSpec(
                schemaName,
                strict,
                schema,
                structuredOutputSettings.requireProviderParameters(request.getPlanningEndpointName()),
                structuredOutputSettings.allowProviderFallbacks()
        ));
    }

    /**
     * 将 LC4j AI Service 返回的 POJO 规范化（normalize）为统一的 {@link LangchainTodoPlan}。
     *
     * <p>主要做以下几件事：
     * <ol>
     *   <li>过滤掉 description 为空的无效 Todo 项</li>
     *   <li>截断到 maxTodos 上限</li>
     *   <li>补全缺失的 id/sequence（LLM 可能不填或填错）</li>
     *   <li>规范化 dependsOn/groupKey/parallelizable 等 DAG 相关字段</li>
     *   <li>统一 status 为 PENDING、创建时间为当前时刻</li>
     * </ol>
     *
     * <p>这个方法是单阶段路径的质量防线——LLM 输出的 JSON 可能缺少字段、
     * 字段值非法、或包含无意义的描述，这里全部兜底处理。
     *
     * @throws IllegalStateException 如果规范化后没有任何有效 Todo 项
     */
    private LangchainTodoPlan normalizeResponse(LangchainTodoPlanResponse response, int maxTodos, PlanExecutionMode mode) {
        if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
            throw new IllegalStateException("todo_plan_empty");
        }
        java.util.List<world.willfrog.agent.workflow.TodoItem> items = new java.util.ArrayList<>();
        int sequence = 1;
        for (LangchainTodoPlanResponse.TodoItemResponse itemResponse : response.getItems()) {
            if (itemResponse == null || isBlank(itemResponse.getDescription())) {
                continue;
            }
            if (items.size() >= maxTodos) {
                break;
            }
            int effectiveSequence = itemResponse.getSequence() != null && itemResponse.getSequence() > 0
                    ? itemResponse.getSequence()
                    : sequence;
            String id = nvl(itemResponse.getId()).trim();
            if (id.isBlank()) {
                id = "todo_" + effectiveSequence;
            }
            items.add(world.willfrog.agent.workflow.TodoItem.builder()
                    .id(id)
                    .sequence(effectiveSequence)
                    .description(itemResponse.getDescription().trim())
                    .dependsOn(sanitizeList(itemResponse.getDependsOn()))
                    .groupKey(blankToNull(itemResponse.getGroupKey()))
                    .parallelizable(Boolean.TRUE.equals(itemResponse.getParallelizable()))
                    .status(world.willfrog.agent.workflow.TodoStatus.PENDING)
                    .createdAt(java.time.Instant.now())
                    .build());
            sequence++;
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("todo_plan_empty");
        }
        return LangchainTodoPlan.builder()
                .analysis(nvl(response.getAnalysis()))
                .items(items)
                .extractedEntities(sanitizeList(response.getExtractedEntities()))
                .executionMode(mode)
                .build();
    }

    /**
     * 解析最大 Todo 数量：取请求值（如果有效）与 Nacos 配置值的较小者。
     * 最终结果 clamp 到 [1, 50]。
     */
    private int resolveMaxTodos(Integer requested) {
        int configured = structuredOutputSettings.resolveMaxTodos(DEFAULT_MAX_TODOS);
        if (requested == null || requested <= 0) {
            return configured;
        }
        return Math.max(1, Math.min(requested, configured));
    }

    private int resolvePlanningMaxAttempts() {
        return DEFAULT_MAX_ATTEMPTS;
    }

    private void validate(LangchainPlanningRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("planning_request_required");
        }
        if (request.getModel() == null) {
            throw new IllegalArgumentException("planning_chat_model_required");
        }
        if (isBlank(request.getUserGoal())) {
            throw new IllegalArgumentException("planning_user_goal_required");
        }
    }

    /**
     * 将工具规范列表转为逗号分隔的工具名，用于注入 planning prompt。
     * 例如 {@code "searchIndex, getStockDaily, executePython"}。
     * 如果没有任何工具，返回字符串 {@code "none"}。
     */
    private String buildToolList(java.util.List<ToolSpecification> specifications) {
        if (specifications == null || specifications.isEmpty()) {
            return "none";
        }
        return specifications.stream()
                .map(ToolSpecification::name)
                .filter(name -> !isBlank(name))
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
    }

    /**
     * 构建工具白名单（Set 形式），注入 planning prompt 供 LLM 参考可用的工具集合。
     * 保留插入顺序（{@link LinkedHashSet}），方便日志排查。
     * Todo 的结构字段校验由 {@link StructuredPlanningSupport#validateTodoPlan} 负责。
     */
    private Set<String> buildToolWhitelist(java.util.List<ToolSpecification> specifications) {
        if (specifications == null || specifications.isEmpty()) {
            return Set.of();
        }
        return specifications.stream()
                .map(ToolSpecification::name)
                .filter(name -> !isBlank(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private java.util.List<String> sanitizeList(java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return java.util.List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = nvl(value).trim();
            if (!normalized.isBlank()) {
                unique.add(normalized);
            }
        }
        return java.util.List.copyOf(unique);
    }

    private String blankToNull(String value) {
        String normalized = nvl(value).trim();
        return normalized.isBlank() ? null : normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    /**
     * 恢复 planning 调用前的 structured output spec。
     * <p>如果之前为 null，调用 {@code clear} 而非 {@code set(null)}，
     * 因为 {@code AgentContext} 的 ThreadLocal 不支持 null 值。
     * 这与 legacy {@code TodoPlanner} 中的 save/restore 模式一致。
     */
    private void restoreStructuredOutputSpec(AgentContext.StructuredOutputSpec previousSpec) {
        if (previousSpec == null) {
            AgentContext.clearStructuredOutputSpec();
        } else {
            AgentContext.setStructuredOutputSpec(previousSpec);
        }
    }

    /**
     * 恢复 planning 调用前的 stage 标记。
     * <p>与 {@link #restoreStructuredOutputSpec} 同理，
     * null 值时调用 {@code clear} 而非 {@code set(null)}。
     */
    private void restoreStage(String previousStage) {
        if (previousStage == null || previousStage.isBlank()) {
            AgentContext.clearStage();
        } else {
            AgentContext.setStage(previousStage);
        }
    }
}

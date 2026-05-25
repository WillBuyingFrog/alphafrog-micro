package world.willfrog.agent.platform.context;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import world.willfrog.agent.platform.config.RunStageConfig;

/**
 * Agent 运行时上下文中枢 —— 跨组件共享的 {@link ThreadLocal} 容器。
 *
 * <h2>为什么需要这个类</h2>
 * <p>一次 Agent Run 涉及几十个组件（Pipeline、Planner、ChatModel、ToolRouter、ObservabilityService 等），
 * 它们都需要访问 runId、userId、当前 phase、todoId 等运行时状态。
 * 如果通过方法参数逐层传递，每个方法签名都要多一堆参数且容易漏传。
 * ThreadLocal 方案让所有组件在同一个线程内隐式共享这些上下文，
 * 同时保证不同 run 之间天然隔离。</p>
 *
 * <h2>面试常被追问的三个点</h2>
 * <ul>
 *   <li><b>"ThreadLocal 不会被线程池复用时串数据吗？"</b>
 *       → 入口 finally 块调 {@link #clear()} 清理所有字段，保证线程归还池时是干净的。</li>
 *   <li><b>"DAG 子线程怎么拿到父线程的 webSearch 开关？"</b>
 *       → {@link #captureRunContext()} / {@link #restoreRunContext(ContextSnapshot)} 快照-还原机制。
 *       父线程拍快照 → 传到子线程 → 子线程还原。历史上缺失这套机制时 webSearch 在子线程永远为 false。</li>
 *   <li><b>"结构化输出（structured output）怎么实现的？"</b>
 *       → {@link StructuredOutputSpec} 存在 ThreadLocal 中，LLM 包装器检测到后自动注入
 *       {@code response_format: json_schema} 到请求体。Planner 在调用前 set，调用后 clear。</li>
 * </ul>
 */
public class AgentContext {
    /** 当前 Run ID,由 AgentRunExecutor 在执行入口设置 */
    private static final ThreadLocal<String> RUN_ID_HOLDER = new ThreadLocal<>();
    /** 当前用户 ID,由 AgentRunExecutor 在执行入口设置 */
    private static final ThreadLocal<String> USER_ID_HOLDER = new ThreadLocal<>();
    /**
     * 当前执行阶段(如 "planning"、"execution"、"final_answer"),
     * 由 LLM 包装器在调用前后切换,供观测系统归类 LLM trace。
     */
    private static final ThreadLocal<String> PHASE_HOLDER = new ThreadLocal<>();
    /** 当前阶段下的细分子阶段(如 "linear_execution_todo_3"),用于观测粒度更细的归类 */
    private static final ThreadLocal<String> STAGE_HOLDER = new ThreadLocal<>();
    /** 当前正在执行的 Todo ID */
    private static final ThreadLocal<String> TODO_ID_HOLDER = new ThreadLocal<>();
    /** 当前 Todo 在 Plan 中的 sequence(顺序号) */
    private static final ThreadLocal<Integer> TODO_SEQUENCE_HOLDER = new ThreadLocal<>();
    /** Sub-Agent 内的步骤索引,用于观测 sub-agent 多步执行的归类 */
    private static final ThreadLocal<Integer> SUB_AGENT_STEP_INDEX_HOLDER = new ThreadLocal<>();
    /** Python 沙箱代码二次重写(refine)的尝试次数,用于观测和限流 */
    private static final ThreadLocal<Integer> PYTHON_REFINE_ATTEMPT_HOLDER = new ThreadLocal<>();
    /** 决策 trace ID,关联到 PlanJudge 等组件产生的决策链 */
    private static final ThreadLocal<String> DECISION_TRACE_ID_HOLDER = new ThreadLocal<>();
    /** 决策所处阶段(配合 traceId 用) */
    private static final ThreadLocal<String> DECISION_STAGE_HOLDER = new ThreadLocal<>();
    /** 决策摘要片段,用于观测系统快速识别决策类型 */
    private static final ThreadLocal<String> DECISION_EXCERPT_HOLDER = new ThreadLocal<>();
    /**
     * 上一次 LLM 调用返回的 provider trace ID(OpenRouter 提供的 generation id),
     * 通过 {@link #consumeProviderLlmTraceId()} 一次性消费,避免被多次记录。
     */
    private static final ThreadLocal<String> PROVIDER_LLM_TRACE_ID_HOLDER = new ThreadLocal<>();
    /**
     * 结构化输出(JSON Schema)规范,LLM 包装器在请求时若发现此项非空,
     * 会自动注入 response_format 以强制模型输出符合 schema 的 JSON。
     */
    private static final ThreadLocal<StructuredOutputSpec> STRUCTURED_OUTPUT_SPEC_HOLDER = new ThreadLocal<>();
    /**
     * 调试模式开关：
     * 每个 run 在执行线程(含并行子线程)里独立保存,避免跨 run 串扰。
     */
    private static final ThreadLocal<Boolean> DEBUG_MODE_HOLDER = new ThreadLocal<>();
    /**
     * 网页搜索能力开关。
     * 由 AgentRunExecutor 根据 run 请求中的 webSearchEnabled 字段设置。
     * 搜索工具在执行前会检查此开关,关闭时直接返回不可用响应。
     * 子线程必须通过 captureRunContext / restoreRunContext 继承,否则会错误地拿到 false。
     */
    private static final ThreadLocal<Boolean> WEB_SEARCH_ENABLED_HOLDER = new ThreadLocal<>();
    /**
     * 网页搜索的详细配置(后端、强度、是否跳过缓存等)。
     * 与 webSearchEnabled 配合使用,搜索工具据此选择具体后端和参数。
     */
    private static final ThreadLocal<WebSearchConfig> WEB_SEARCH_CONFIG_HOLDER = new ThreadLocal<>();
    /**
     * Planner 从用户目标中抽取的实体列表(如"沪深300"、"中证500"),
     * 后续搜索证据相关性判断、引用筛选等环节会用到。
     */
    private static final ThreadLocal<List<String>> EXTRACTED_ENTITIES_HOLDER = new ThreadLocal<>();

    /**
     * OpenRouter reasoning (thinking) effort 配置：
     * 用于控制 reasoning 模型的思考强度,仅在 OpenRouter 端点生效。
     */
    private static final ThreadLocal<String> REASONING_EFFORT_HOLDER = new ThreadLocal<>();

    /**
     * 当前 Run 的阶段级 LLM 配置(客户端+本地合并后的最终结果)。
     */
    private static final ThreadLocal<RunStageConfig> STAGE_CONFIG_HOLDER = new ThreadLocal<>();

    /**
     * DashScope thinking 内容：从流式响应中提取的 reasoning_content。
     */
    private static final ThreadLocal<String> THINKING_CONTENT_HOLDER = new ThreadLocal<>();

    /**
     * 流式响应实时进度快照。
     */
    private static final ThreadLocal<world.willfrog.agent.platform.service.StreamingProgressTracker.StreamingProgressSnapshot> STREAMING_PROGRESS_HOLDER = new ThreadLocal<>();

    /** 设置当前线程的 Run ID。 */
    public static void setRunId(String runId) {
        RUN_ID_HOLDER.set(runId);
    }

    /** 获取当前线程的 Run ID,可能为 null(未设置时)。 */
    public static String getRunId() {
        return RUN_ID_HOLDER.get();
    }

    /** 设置当前线程的用户 ID。 */
    public static void setUserId(String userId) {
        USER_ID_HOLDER.set(userId);
    }

    /** 获取当前线程的用户 ID。 */
    public static String getUserId() {
        return USER_ID_HOLDER.get();
    }

    /** 设置当前执行阶段(planning / execution / final_answer 等)。 */
    public static void setPhase(String phase) {
        PHASE_HOLDER.set(phase);
    }

    /** 获取当前执行阶段。 */
    public static String getPhase() {
        return PHASE_HOLDER.get();
    }

    /** 设置当前阶段下的细分子阶段。 */
    public static void setStage(String stage) {
        STAGE_HOLDER.set(stage);
    }

    /** 获取当前细分子阶段。 */
    public static String getStage() {
        return STAGE_HOLDER.get();
    }

    /**
     * 同时设置当前 Todo ID 与其 sequence,
     * 调用方一般是 LinearWorkflowExecutor / DagWorkflowExecutor 在进入下一个 Todo 时。
     */
    public static void setTodoContext(String todoId, Integer sequence) {
        TODO_ID_HOLDER.set(todoId);
        TODO_SEQUENCE_HOLDER.set(sequence);
    }

    /** 获取当前 Todo ID。 */
    public static String getTodoId() {
        return TODO_ID_HOLDER.get();
    }

    /** 获取当前 Todo 的 sequence。 */
    public static Integer getTodoSequence() {
        return TODO_SEQUENCE_HOLDER.get();
    }

    /** 设置 Sub-Agent 的步骤索引。 */
    public static void setSubAgentStepIndex(Integer stepIndex) {
        SUB_AGENT_STEP_INDEX_HOLDER.set(stepIndex);
    }

    /** 获取 Sub-Agent 的步骤索引。 */
    public static Integer getSubAgentStepIndex() {
        return SUB_AGENT_STEP_INDEX_HOLDER.get();
    }

    /** 设置 Python 沙箱 refine(代码修正)的尝试次数。 */
    public static void setPythonRefineAttempt(Integer attempt) {
        PYTHON_REFINE_ATTEMPT_HOLDER.set(attempt);
    }

    /** 获取 Python 沙箱 refine 的尝试次数。 */
    public static Integer getPythonRefineAttempt() {
        return PYTHON_REFINE_ATTEMPT_HOLDER.get();
    }

    /**
     * 一次性写入决策上下文三元组(traceId / stage / excerpt),
     * 通常由 PlanJudge 在做出决策后调用。
     *
     * @param traceId 决策链 trace ID
     * @param stage   决策所处阶段
     * @param excerpt 决策摘要片段
     */
    public static void setDecisionContext(String traceId, String stage, String excerpt) {
        DECISION_TRACE_ID_HOLDER.set(traceId);
        DECISION_STAGE_HOLDER.set(stage);
        DECISION_EXCERPT_HOLDER.set(excerpt);
    }

    /** 获取决策链 trace ID。 */
    public static String getDecisionTraceId() {
        return DECISION_TRACE_ID_HOLDER.get();
    }

    /** 获取决策所处阶段。 */
    public static String getDecisionStage() {
        return DECISION_STAGE_HOLDER.get();
    }

    /** 获取决策摘要片段。 */
    public static String getDecisionExcerpt() {
        return DECISION_EXCERPT_HOLDER.get();
    }

    /**
     * 设置结构化输出规范。LLM 包装器在准备请求体时会检查此项,
     * 非空则注入 {@code response_format = json_schema} 强制 JSON 输出。
     */
    public static void setStructuredOutputSpec(StructuredOutputSpec spec) {
        STRUCTURED_OUTPUT_SPEC_HOLDER.set(spec);
    }

    /** 获取当前的结构化输出规范,可能为 null。 */
    public static StructuredOutputSpec getStructuredOutputSpec() {
        return STRUCTURED_OUTPUT_SPEC_HOLDER.get();
    }

    /** 设置调试模式开关(由 AgentRunExecutor 从 run.ext 读取)。 */
    public static void setDebugMode(boolean debugMode) {
        DEBUG_MODE_HOLDER.set(debugMode);
    }

    /**
     * 判断当前线程是否处于调试模式。
     * null safe：未设置时返回 false。
     */
    public static boolean isDebugMode() {
        Boolean enabled = DEBUG_MODE_HOLDER.get();
        return enabled != null && enabled;
    }

    /** 设置网页搜索能力开关。 */
    public static void setWebSearchEnabled(boolean enabled) {
        WEB_SEARCH_ENABLED_HOLDER.set(enabled);
    }

    /**
     * 判断当前线程是否启用了网页搜索。
     * null safe：未设置时返回 false(默认关闭,要求显式开启)。
     */
    public static boolean isWebSearchEnabled() {
        Boolean enabled = WEB_SEARCH_ENABLED_HOLDER.get();
        return enabled != null && enabled;
    }

    /** 清理网页搜索开关。 */
    public static void clearWebSearchEnabled() {
        WEB_SEARCH_ENABLED_HOLDER.remove();
    }

    /**
     * 设置网页搜索详细配置。
     * 传入 null 等价于清理,避免误覆盖。
     */
    public static void setWebSearchConfig(WebSearchConfig config) {
        if (config == null) {
            WEB_SEARCH_CONFIG_HOLDER.remove();
            return;
        }
        WEB_SEARCH_CONFIG_HOLDER.set(config);
    }

    /**
     * 获取网页搜索配置。
     * null safe：未设置时返回 {@link WebSearchConfig#empty()},消费方可直接 .backend() 等。
     */
    public static WebSearchConfig getWebSearchConfig() {
        WebSearchConfig config = WEB_SEARCH_CONFIG_HOLDER.get();
        return config == null ? WebSearchConfig.empty() : config;
    }

    /** 清理网页搜索配置。 */
    public static void clearWebSearchConfig() {
        WEB_SEARCH_CONFIG_HOLDER.remove();
    }

    /**
     * 设置 Planner 提取的实体列表。
     * 空列表/ null 等价于清理。存储时做不可变拷贝,避免外部修改影响 ThreadLocal 数据。
     */
    public static void setExtractedEntities(List<String> entities) {
        if (entities == null || entities.isEmpty()) {
            EXTRACTED_ENTITIES_HOLDER.remove();
            return;
        }
        EXTRACTED_ENTITIES_HOLDER.set(List.copyOf(entities));
    }

    /**
     * 获取提取的实体列表。
     * null safe：未设置时返回空列表,消费方可直接遍历。
     */
    public static List<String> getExtractedEntities() {
        List<String> entities = EXTRACTED_ENTITIES_HOLDER.get();
        return entities == null ? List.of() : entities;
    }

    /** 清理实体列表。 */
    public static void clearExtractedEntities() {
        EXTRACTED_ENTITIES_HOLDER.remove();
    }

    /** 清理调试模式标志。 */
    public static void clearDebugMode() {
        DEBUG_MODE_HOLDER.remove();
    }

    /**
     * 设置 OpenRouter reasoning effort(如 "high"、"medium"、"low")。
     * LLM 包装器据此为 OpenRouter 端点注入 reasoning 参数。
     */
    public static void setReasoningEffort(String effort) {
        REASONING_EFFORT_HOLDER.set(effort);
    }

    /** 获取 reasoning effort 值,可能为 null。 */
    public static String getReasoningEffort() {
        return REASONING_EFFORT_HOLDER.get();
    }

    /** 清理 reasoning effort。 */
    public static void clearReasoningEffort() {
        REASONING_EFFORT_HOLDER.remove();
    }

    /**
     * 设置当前 Run 的阶段级 LLM 配置(planning / execution / final_answer)。
     * 由 AgentRunExecutor 在解析合并完客户端与本地配置后写入。
     */
    public static void setStageConfig(RunStageConfig config) {
        STAGE_CONFIG_HOLDER.set(config);
    }

    /** 获取阶段级 LLM 配置,可能为 null。 */
    public static RunStageConfig getStageConfig() {
        return STAGE_CONFIG_HOLDER.get();
    }

    /** 清理阶段级 LLM 配置。 */
    public static void clearStageConfig() {
        STAGE_CONFIG_HOLDER.remove();
    }

    /** 设置流式响应中提取的 thinking 内容(DashScope reasoning_content 等)。 */
    public static void setThinkingContent(String content) {
        THINKING_CONTENT_HOLDER.set(content);
    }

    /** 获取流式 thinking 内容。 */
    public static String getThinkingContent() {
        return THINKING_CONTENT_HOLDER.get();
    }

    /** 清理流式 thinking 内容。 */
    public static void clearThinkingContent() {
        THINKING_CONTENT_HOLDER.remove();
    }

    /** 设置流式进度快照,供观测查询接口实时读取。 */
    public static void setStreamingProgress(world.willfrog.agent.platform.service.StreamingProgressTracker.StreamingProgressSnapshot snapshot) {
        STREAMING_PROGRESS_HOLDER.set(snapshot);
    }

    /** 获取流式进度快照。 */
    public static world.willfrog.agent.platform.service.StreamingProgressTracker.StreamingProgressSnapshot getStreamingProgress() {
        return STREAMING_PROGRESS_HOLDER.get();
    }

    /** 清理流式进度快照。 */
    public static void clearStreamingProgress() {
        STREAMING_PROGRESS_HOLDER.remove();
    }

    /** 清理 phase。 */
    public static void clearPhase() {
        PHASE_HOLDER.remove();
    }

    /** 清理 stage。 */
    public static void clearStage() {
        STAGE_HOLDER.remove();
    }

    /** 同时清理 Todo ID 与 sequence。 */
    public static void clearTodoContext() {
        TODO_ID_HOLDER.remove();
        TODO_SEQUENCE_HOLDER.remove();
    }

    /** 清理 Sub-Agent 步骤索引。 */
    public static void clearSubAgentStepIndex() {
        SUB_AGENT_STEP_INDEX_HOLDER.remove();
    }

    /** 清理 Python refine 尝试次数。 */
    public static void clearPythonRefineAttempt() {
        PYTHON_REFINE_ATTEMPT_HOLDER.remove();
    }

    /** 一次性清理决策上下文三元组。 */
    public static void clearDecisionContext() {
        DECISION_TRACE_ID_HOLDER.remove();
        DECISION_STAGE_HOLDER.remove();
        DECISION_EXCERPT_HOLDER.remove();
    }

    /**
     * 设置 provider 侧 LLM trace ID(OpenRouter 等返回的 generation id)。
     * 空白值等价于清理,避免误把空字符串当成有效 trace。
     */
    public static void setProviderLlmTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            PROVIDER_LLM_TRACE_ID_HOLDER.remove();
            return;
        }
        PROVIDER_LLM_TRACE_ID_HOLDER.set(traceId);
    }

    /**
     * 一次性消费 provider trace ID：读取并立即清理。
     *
     * <p>用于将 trace 关联到本次 LLM 调用的观测记录,
     * 消费后立即清理,避免下次调用错误地继承上次的 trace。</p>
     *
     * @return provider trace ID,可能为 null
     */
    public static String consumeProviderLlmTraceId() {
        String traceId = PROVIDER_LLM_TRACE_ID_HOLDER.get();
        PROVIDER_LLM_TRACE_ID_HOLDER.remove();
        return traceId;
    }

    /** 清理结构化输出规范。 */
    public static void clearStructuredOutputSpec() {
        STRUCTURED_OUTPUT_SPEC_HOLDER.remove();
    }

    /**
     * 拍下当前线程关键的 run 级上下文快照,用于跨线程传递。
     *
     * <h4>用途</h4>
     * 当主线程要把任务交给子线程(DAG 并行 Todo、Sub-Agent 等)执行时,
     * 必须先在主线程调用本方法拍快照,再把快照交给子线程,在子线程开始执行前
     * 调用 {@link #restoreRunContext(ContextSnapshot)} 还原。
     *
     * <h4>历史背景</h4>
     * 早期 DAG 子线程没有这套快照机制,导致父线程开启 webSearch 但子线程
     * 看不到这个标志(因为 ThreadLocal 不会自动跨线程传递),搜索工具误以为
     * 用户没开搜索而拒绝执行。引入本方法后,子线程能正确继承父线程的所有能力配置。
     *
     * @return 包含 runId / userId / debugMode / webSearchEnabled / webSearchConfig /
     *         extractedEntities / reasoningEffort / stageConfig 的不可变快照
     */
    public static ContextSnapshot captureRunContext() {
        return new ContextSnapshot(
                getRunId(),
                getUserId(),
                DEBUG_MODE_HOLDER.get(),
                WEB_SEARCH_ENABLED_HOLDER.get(),
                WEB_SEARCH_CONFIG_HOLDER.get(),
                EXTRACTED_ENTITIES_HOLDER.get(),
                getReasoningEffort(),
                getStageConfig()
        );
    }

    /**
     * 在子线程中根据快照还原 run 级上下文。
     *
     * <p>对每个字段做"快照值非空则 set,否则 remove"的精确还原,
     * 避免线程池复用线程时残留旧 run 的 ThreadLocal 值。
     * 这是子线程能正确继承父线程能力配置(尤其是 webSearch 开关)的根本机制。</p>
     *
     * @param snapshot 父线程通过 {@link #captureRunContext()} 拍下的快照,null 时直接返回
     */
    public static void restoreRunContext(ContextSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        // runId / userId:null 时清理,否则 set
        if (snapshot.runId() == null) {
            RUN_ID_HOLDER.remove();
        } else {
            setRunId(snapshot.runId());
        }
        if (snapshot.userId() == null) {
            USER_ID_HOLDER.remove();
        } else {
            setUserId(snapshot.userId());
        }
        // debugMode:Boolean 字段需用包装类型保留 null 语义,避免 false 被误覆盖
        if (snapshot.debugMode() == null) {
            clearDebugMode();
        } else {
            setDebugMode(snapshot.debugMode());
        }
        // webSearchEnabled / webSearchConfig:子线程沿用父线程开关
        if (snapshot.webSearchEnabled() == null) {
            clearWebSearchEnabled();
        } else {
            setWebSearchEnabled(snapshot.webSearchEnabled());
        }
        if (snapshot.webSearchConfig() == null) {
            clearWebSearchConfig();
        } else {
            setWebSearchConfig(snapshot.webSearchConfig());
        }
        // 实体列表
        if (snapshot.extractedEntities() == null) {
            clearExtractedEntities();
        } else {
            setExtractedEntities(snapshot.extractedEntities());
        }
        // reasoning effort:子线程同样需要继承,否则推理强度被默默丢失
        if (snapshot.reasoningEffort() == null) {
            clearReasoningEffort();
        } else {
            setReasoningEffort(snapshot.reasoningEffort());
        }
        // 阶段级 LLM 配置:子线程切换阶段时仍需要原始 stage_config
        if (snapshot.stageConfig() == null) {
            clearStageConfig();
        } else {
            setStageConfig(snapshot.stageConfig());
        }
    }

    /**
     * 清理本线程所有 ThreadLocal 字段。
     *
     * <p>务必在 run 执行入口的 finally 块中调用,
     * 否则线程池复用线程时会发生跨 run 的 ThreadLocal 串扰
     * (一个 run 的 runId 被另一个 run 误用、debugMode 错位、webSearch 错位等)。</p>
     */
    public static void clear() {
        RUN_ID_HOLDER.remove();
        USER_ID_HOLDER.remove();
        clearPhase();
        clearStage();
        clearTodoContext();
        clearSubAgentStepIndex();
        clearPythonRefineAttempt();
        clearDecisionContext();
        PROVIDER_LLM_TRACE_ID_HOLDER.remove();
        clearStructuredOutputSpec();
        clearDebugMode();
        clearWebSearchEnabled();
        clearWebSearchConfig();
        clearExtractedEntities();
        clearReasoningEffort();
        clearStageConfig();
        clearThinkingContent();
        clearStreamingProgress();
    }

    /**
     * 网页搜索详细配置。
     *
     * @param backend          搜索后端名称(如 "google"、"bing"、"tavily")
     * @param strength         搜索强度("light" / "balanced" / "deep" 等)
     * @param skipHotCache     是否跳过热缓存(强制实时搜索)
     * @param skipRagPrefetch  是否跳过 RAG 预取(直接搜索而非先查内部知识库)
     * @param maxResults       最大返回结果数
     */
    public record WebSearchConfig(
            String backend,
            String strength,
            Boolean skipHotCache,
            Boolean skipRagPrefetch,
            Integer maxResults
    ) {
        /** 返回所有字段为空/null 的占位 config,用于 getter null safe 兜底。 */
        public static WebSearchConfig empty() {
            return new WebSearchConfig("", "", null, null, null);
        }
    }

    /**
     * Run 级上下文快照,用于父线程 → 子线程的状态传递。
     *
     * <p>包含的字段都是子线程在执行任务时必需的 run 级配置,
     * 不包含 todoId / stage 等会随子线程任务变化的字段。</p>
     *
     * @param runId               Run ID
     * @param userId              用户 ID
     * @param debugMode           调试模式开关(Boolean 包装类型,null 表示未设置)
     * @param webSearchEnabled    网页搜索开关(Boolean 包装类型,null 表示未设置)
     * @param webSearchConfig     网页搜索详细配置
     * @param extractedEntities   Planner 提取的实体列表
     * @param reasoningEffort     OpenRouter reasoning 强度
     * @param stageConfig         阶段级 LLM 配置
     */
    public record ContextSnapshot(
            String runId,
            String userId,
            Boolean debugMode,
            Boolean webSearchEnabled,
            WebSearchConfig webSearchConfig,
            List<String> extractedEntities,
            String reasoningEffort,
            RunStageConfig stageConfig
    ) {
    }

    /**
     * LLM 结构化输出规范。
     *
     * <p>当此 spec 被设置到 ThreadLocal 后,LLM 包装器会自动在请求体里注入
     * {@code response_format: { type: "json_schema", json_schema: {...} }},
     * 强制 LLM 输出符合 schema 的 JSON。常用于让 LLM 输出 Plan、PlanPatch、
     * 结构化最终答案等格式严格的数据。</p>
     *
     * <p>字段:</p>
     * <ul>
     *   <li>{@code schemaName} — schema 名称,作为标识传给 provider</li>
     *   <li>{@code strict} — 是否启用严格模式(provider 严格按 schema 校验)</li>
     *   <li>{@code schema} — JSON Schema 内容</li>
     *   <li>{@code requireProviderParameters} — 是否要求 provider 支持 parameters 字段
     *       (用于 OpenRouter 上筛选支持结构化输出的 provider)</li>
     *   <li>{@code allowProviderFallbacks} — 是否允许在主 provider 不支持结构化输出时
     *       回退到其他 provider</li>
     * </ul>
     */
    public static final class StructuredOutputSpec {
        private final String schemaName;
        private final boolean strict;
        private final Map<String, Object> schema;
        private final boolean requireProviderParameters;
        private final boolean allowProviderFallbacks;

        /**
         * 构造结构化输出 spec。对入参做空安全保护:
         * schemaName 为 null 时用空串,schema 为 null 时用空 Map。
         * 同时对 schema 做不可变拷贝,避免外部修改影响线程上下文。
         */
        public StructuredOutputSpec(String schemaName,
                                    boolean strict,
                                    Map<String, Object> schema,
                                    boolean requireProviderParameters,
                                    boolean allowProviderFallbacks) {
            this.schemaName = schemaName == null ? "" : schemaName;
            this.strict = strict;
            this.schema = schema == null ? Map.of() : Map.copyOf(schema);
            this.requireProviderParameters = requireProviderParameters;
            this.allowProviderFallbacks = allowProviderFallbacks;
        }

        public String schemaName() {
            return schemaName;
        }

        public boolean strict() {
            return strict;
        }

        public Map<String, Object> schema() {
            return schema;
        }

        public boolean requireProviderParameters() {
            return requireProviderParameters;
        }

        public boolean allowProviderFallbacks() {
            return allowProviderFallbacks;
        }

        /**
         * 将本 spec 序列化为 OpenAI 兼容的 {@code response_format} 字段结构。
         *
         * <p>返回的结构:</p>
         * <pre>
         * {
         *   "type": "json_schema",
         *   "json_schema": {
         *     "name": &lt;schemaName&gt;,
         *     "strict": &lt;strict&gt;,
         *     "schema": { ... }
         *   }
         * }
         * </pre>
         *
         * @return 可直接放入 LLM 请求体的 response_format Map
         */
        public Map<String, Object> asResponseFormat() {
            Map<String, Object> jsonSchema = new LinkedHashMap<>();
            jsonSchema.put("name", schemaName);
            jsonSchema.put("strict", strict);
            jsonSchema.put("schema", schema);

            Map<String, Object> responseFormat = new LinkedHashMap<>();
            responseFormat.put("type", "json_schema");
            responseFormat.put("json_schema", jsonSchema);
            return responseFormat;
        }
    }
}

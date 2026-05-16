package world.willfrog.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Agent 提示词中心,集中管理所有阶段的 System Prompt / Stage Instruction / 模板渲染。
 *
 * <h3>提示词体系</h3>
 * <ul>
 *   <li><b>全局 System Prompt</b>:{@code agentRunSystemPrompt} 角色与全局指令,在所有阶段共享。</li>
 *   <li><b>阶段 System Prompt</b>:为每个阶段提供专用 prompt(planner / final / recovery / judge 等)。</li>
 *   <li><b>Stage Instruction</b>(#28 重构):为兼顾 KV 前缀缓存命中率,统一 System Prompt 改为静态,
 *       阶段差异通过 {@link #planningAnalysisStageInstruction}、
 *       {@link #finalAnswerStageInstruction}、{@link #planJudgeStageInstruction}
 *       等方法返回的指令注入到 User Message。</li>
 *   <li><b>DAG ReAct Prompt</b>:{@link #dagReactSystemPrompt()} 为 DAG 节点执行专用的 ReAct prompt,
 *       由 ReactTodoExecutor 在构造系统消息时使用。</li>
 * </ul>
 *
 * <h3>动态内容隔离</h3>
 * 日期等会逐天变化的内容已从 System Prompt 中移除,改为通过 {@link #dynamicContextPrefix()}
 * 注入到 User Message 开头。这样 System Prompt 在整次 run 中字节级一致,
 * 最大化 Fireworks / OpenRouter 的 KV 前缀缓存命中率。
 *
 * <h3>配置加载优先级</h3>
 * 所有 prompts 字段都通过 {@link #currentPrompts()} 合并加载,优先级为:
 * <ol>
 *   <li>{@code agent-llm.local.json} 本地热加载配置(支持不重启热更新)</li>
 *   <li>{@code application.yml} 静态 properties 配置</li>
 *   <li>类路径内置默认 prompt 文件(如 {@code prompts/todo/dag_react_system_default.txt})</li>
 * </ol>
 *
 * <h3>Sub-Agent 模型智能选择</h3>
 * 通过 {@link #selectSubAgentModelName(String, String)} 根据目标长度、关键词复杂度等启发式特征
 * 估算复杂度(LOW / MEDIUM / HIGH),再选择配置中对应级别的 sub-agent 模型;
 * 同时支持回退到 fallback 模型,确保任何复杂度都能返回有效模型。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentPromptService {

    /** 中文日期格式化器,用于 dynamicContextPrefix 中的"今天是 yyyy年MM月dd日" */
    private static final DateTimeFormatter CN_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日");
    /** 静态 LLM 配置(application.yml),作为热加载配置的 fallback */
    private final AgentLlmProperties properties;
    /** 本地热加载配置(agent-llm.local.json),支持不重启热更新 */
    private final AgentLlmLocalConfigLoader localConfigLoader;

    /**
     * 返回 Agent Run 入口的系统提示词(完整版,会经过 composeSystemPrompt 拼接全局指令)。
     * 主要供旧调用方使用,新代码倾向使用 {@link #reactSystemPrompt()} 配合 stage instruction。
     */
    public String agentRunSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getAgentRunSystemPrompt(), ""));
    }

    /**
     * Todo Planner 的系统提示词(用于让 LLM 把用户目标拆解为 Todo List)。
     *
     * <p>模板中的 {@code {{toolWhitelist}}} 和 {@code {{maxTodos}}} 占位符会被替换。
     * 若配置中未指定模板,使用内置默认值(强约束 JSON 输出格式)。</p>
     *
     * @param toolWhitelist 可用工具名白名单,逗号分隔
     * @param maxTodos      总步骤上限
     * @return 拼接全局指令后的系统提示词
     */
    public String todoPlannerSystemPrompt(String toolWhitelist, int maxTodos) {
        String template = firstNonBlank(
                currentPrompts().getTodoPlannerSystemPromptTemplate(),
                """
                你是任务规划专家。请把用户目标拆解为 Todo List，只输出 JSON。
                输出格式:
                {"analysis":"...","items":[{"id":"todo_1","sequence":1,"type":"TOOL_CALL","toolName":"searchIndex","params":{"keyword":"沪深300"},"reasoning":"...","executionMode":"AUTO"}]}
                规则:
                1) 只能使用工具: {{toolWhitelist}}
                2) 总步骤数不超过 {{maxTodos}}
                3) type 仅允许 TOOL_CALL/SUB_AGENT/THOUGHT
                4) executionMode 仅允许 AUTO/FORCE_SIMPLE/FORCE_SUB_AGENT
                """
        );
        String specific = render(template, Map.of(
                "toolWhitelist", safe(toolWhitelist),
                "maxTodos", String.valueOf(maxTodos)
        ));
        return composeSystemPrompt(specific);
    }

    /**
     * 返回需要由调用方注入到 user message 的动态上下文前缀。
     *
     * <p>日期等动态内容已从 system prompt 中完全移除，调用方<b>必须</b>
     * 将此前缀注入到 user message 开头，以确保 LLM 获得正确的时间上下文。</p>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * String systemPrompt = promptService.todoPlannerSystemPrompt(tools, max);
     * String dynamicPrefix = promptService.dynamicContextPrefix();
     * List<ChatMessage> messages = List.of(
     *     new SystemMessage(systemPrompt),
     *     new UserMessage(dynamicPrefix + "\n" + userRequest)
     * );
     * }</pre>
     */
    public String dynamicContextPrefix() {
        return "今天是" + LocalDate.now().format(CN_DATE_FORMATTER) + "。";
    }

    /**
     * 工作流最终回答阶段的 System Prompt。
     * 兼容 {@code workflowFinalSystemPrompt} 与历史的 {@code parallelFinalSystemPrompt} 命名。
     */
    public String workflowFinalSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(
                currentPrompts().getWorkflowFinalSystemPrompt(),
                currentPrompts().getParallelFinalSystemPrompt(),
                ""
        ));
    }

    /**
     * Todo 失败恢复阶段的 System Prompt。
     * 用于让 LLM 在某个 Todo 失败后生成可重试的恢复参数。
     */
    public String workflowTodoRecoverySystemPrompt() {
        return composeSystemPrompt(firstNonBlank(
                currentPrompts().getWorkflowTodoRecoverySystemPrompt(),
                ""
        ));
    }

    /**
     * 并行规划器系统提示词(默认 candidateIndex=1, candidateCount=1)。
     * 委托到带 candidate 参数的重载版本。
     */
    public String parallelPlannerSystemPrompt(String toolWhitelist,
                                              int maxTasks,
                                              int maxSubSteps,
                                              int maxParallelTasks,
                                              int maxSubAgents) {
        return parallelPlannerSystemPrompt(toolWhitelist, maxTasks, maxSubSteps, maxParallelTasks, maxSubAgents, 1, 1);
    }

    /**
     * 并行规划器系统提示词(带 candidate 配置)。
     *
     * <p>当一次 run 同时生成多个并行候选方案时,candidateIndex / candidateCount
     * 用于让 LLM 知道自己是第几个候选,从而生成有差异的 Plan。</p>
     *
     * @param toolWhitelist    可用工具白名单
     * @param maxTasks         总任务上限
     * @param maxSubSteps      单任务子步骤上限
     * @param maxParallelTasks 并行任务上限
     * @param maxSubAgents     Sub-Agent 数量上限
     * @param candidateIndex   候选序号(1 起)
     * @param candidateCount   候选总数
     */
    public String parallelPlannerSystemPrompt(String toolWhitelist,
                                              int maxTasks,
                                              int maxSubSteps,
                                              int maxParallelTasks,
                                              int maxSubAgents,
                                              int candidateIndex,
                                              int candidateCount) {
        String template = firstNonBlank(currentPrompts().getParallelPlannerSystemPromptTemplate(),
                "");
        String specific = render(template, Map.of(
                "toolWhitelist", safe(toolWhitelist),
                "maxTasks", String.valueOf(maxTasks),
                "maxSubSteps", String.valueOf(maxSubSteps),
                "maxParallelTasks", String.valueOf(maxParallelTasks),
                "maxSubAgents", String.valueOf(maxSubAgents),
                // 保底:索引/计数至少为 1,避免渲染出 0/0 这种诡异语义
                "candidateIndex", String.valueOf(Math.max(candidateIndex, 1)),
                "candidateCount", String.valueOf(Math.max(candidateCount, 1))
        ));
        return composeSystemPrompt(specific);
    }

    /** 并行执行结束后的最终汇总 System Prompt。 */
    public String parallelFinalSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getParallelFinalSystemPrompt(), ""));
    }

    /** 并行 Plan 补丁阶段的 System Prompt。 */
    public String parallelPatchPlannerSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getParallelPatchPlannerSystemPromptTemplate(), ""));
    }

    /** Plan Judge(静态判定)的 System Prompt。 */
    public String planJudgeSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getPlanJudgeSystemPromptTemplate(), ""));
    }

    /**
     * Plan Judge 的运行时版本 System Prompt。
     *
     * <p>当配置了 {@code planJudgeRuntimeSystemPromptTemplate} 时优先使用;
     * 否则向后兼容,退回 {@link #planJudgeSystemPrompt()}。</p>
     */
    public String planJudgeRuntimeSystemPrompt() {
        // 优先使用运行时 Plan Judge prompt，如果没有配置则使用默认的
        String runtimePrompt = firstNonBlank(currentPrompts().getPlanJudgeRuntimeSystemPromptTemplate(), "");
        if (!runtimePrompt.isEmpty()) {
            return composeSystemPrompt(runtimePrompt);
        }
        // 向后兼容：如果没有配置运行时 prompt，使用原来的 prompt
        return planJudgeSystemPrompt();
    }

    /** 语义判断器(SemanticJudge)的 System Prompt。 */
    public String semanticJudgeSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getSemanticJudgeSystemPromptTemplate(), ""));
    }

    /**
     * Sub-Agent 内部规划器的 System Prompt。
     *
     * @param tools    Sub-Agent 可用工具白名单
     * @param maxSteps Sub-Agent 内部最大步骤数
     */
    public String subAgentPlannerSystemPrompt(String tools, int maxSteps) {
        String template = firstNonBlank(currentPrompts().getSubAgentPlannerSystemPromptTemplate(),
                "");
        String specific = render(template, Map.of(
                "tools", safe(tools),
                "maxSteps", String.valueOf(maxSteps)
        ));
        return composeSystemPrompt(specific);
    }

    /** Sub-Agent 完成后做内容总结的 System Prompt。 */
    public String subAgentSummarySystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getSubAgentSummarySystemPrompt(), ""));
    }

    /** Python 代码 refine(自动修正)阶段的 System Prompt。 */
    public String pythonRefineSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getPythonRefineSystemPrompt(), ""));
    }

    /**
     * Python refine 阶段的硬性需求清单(分项要求)。
     * 用于在 prompt 中以条目方式罗列必须满足的约束(如"必须使用 pandas")。
     * 自动过滤空白项。
     *
     * @return 不可变的需求列表(可能为空)
     */
    public List<String> pythonRefineRequirements() {
        List<String> requirements = currentPrompts().getPythonRefineRequirements();
        if (requirements == null || requirements.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : requirements) {
            if (item != null && !item.trim().isEmpty()) {
                out.add(item.trim());
            }
        }
        return out;
    }

    /** Python refine 阶段的输出格式指令(如"输出 markdown 表格"等)。 */
    public String pythonRefineOutputInstruction() {
        return firstNonBlank(currentPrompts().getPythonRefineOutputInstruction());
    }

    /**
     * 数据集字段说明(供 LLM 理解可用字段含义和格式)。
     *
     * <p>从配置的 {@code datasetFieldSpecs} 列表渲染为:</p>
     * <pre>
     * - 字段名 | 含义: xxx | 类型: xxx | 格式: xxx
     * </pre>
     *
     * @return 多行文本,无配置时返回空串
     */
    public String pythonRefineDatasetFieldGuide() {
        List<AgentLlmProperties.DatasetFieldSpec> fields = currentPrompts().getDatasetFieldSpecs();
        if (fields == null || fields.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (AgentLlmProperties.DatasetFieldSpec field : fields) {
            if (field == null) {
                continue;
            }
            String name = safe(field.getName());
            if (name.isBlank()) {
                continue;
            }
            String line = "- " + name
                    + " | 含义: " + firstNonBlank(field.getMeaning(), "未说明")
                    + " | 类型: " + firstNonBlank(field.getDataType(), "未说明")
                    + " | 格式: " + firstNonBlank(field.getDataFormat(), "未说明");
            lines.add(line);
        }
        return String.join("\n", lines);
    }

    /** Orchestrator(任务编排)规划阶段的 System Prompt。 */
    public String orchestratorPlanningSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getOrchestratorPlanningSystemPrompt(), ""));
    }

    /** Orchestrator 总结阶段的 System Prompt。 */
    public String orchestratorSummarySystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getOrchestratorSummarySystemPrompt(), ""));
    }

    /**
     * DAG ReAct 执行阶段的 System Prompt。
     * 用于 ReactTodoExecutor 构建 DAG 节点执行时的 System Message。
     *
     * 加载优先级：
     * 1. agent.llm.prompts.dagReactSystemPrompt（直接配置内容）
     * 2. agent.llm.prompts.dagReactSystemPromptFile（由 LocalConfigLoader 解析为文本，与其他 file: 字段机制一致）
     * 3. classpath 默认 Prompt（dag_react_system_default.txt）
     */
    public String dagReactSystemPrompt() {
        return firstNonBlank(
                currentPrompts().getDagReactSystemPrompt(),
                currentPrompts().getDagReactSystemPromptFile(),
                defaultDagReactSystemPrompt()
        );
    }

    /**
     * DAG 模式引导提示（用于规划阶段）。
     * 优先级：
     * 1) dagModeGuidancePrompt
     * 2) dagModeGuidancePromptFile（已由 local config loader 解析为文本）
     */
    public String dagModeGuidancePrompt() {
        return firstNonBlank(
                currentPrompts().getDagModeGuidancePrompt(),
                currentPrompts().getDagModeGuidancePromptFile(),
                ""
        );
    }

    /**
     * 返回配置的最大并行子代理数量，默认 3。
     * 从 agent.llm.runtime.subAgent.maxCount 读取，在 agent-llm.local.json 中设置。
     */
    public int maxSubAgentCount() {
        try {
            AgentLlmProperties.SubAgent subAgent = currentSubAgentConfig();
            if (subAgent != null && subAgent.getMaxCount() != null && subAgent.getMaxCount() > 0) {
                return subAgent.getMaxCount();
            }
        } catch (Exception e) {
            log.warn("Failed to read maxSubAgentCount from config, using default 3: {}", e.getMessage());
        }
        return 3;
    }

    /**
     * Sub-Agent 端点选择（为空表示沿用主代理模型）。
     */
    public String subAgentEndpointName() {
        AgentLlmProperties.SubAgent cfg = currentSubAgentConfig();
        return cfg == null ? "" : firstNonBlank(cfg.getEndpointName(), "");
    }

    /**
     * 根据目标复杂度选择 Sub-Agent 模型名称（仅负责选择与透传，不直接创建模型实例）。
     *
     * <p>选择流程:</p>
     * <ol>
     *   <li>通过 {@link #estimateComplexity} 根据 goal+context 估算 LOW/MEDIUM/HIGH。</li>
     *   <li>按估算结果优先取对应级别的模型名。</li>
     *   <li>对应级别为空则按 HIGH→MEDIUM→LOW→fallback 顺序回退,确保最终拿到非空模型名。</li>
     * </ol>
     */
    public String selectSubAgentModelName(String goal, String context) {
        AgentLlmProperties.SubAgent cfg = currentSubAgentConfig();
        if (cfg == null) {
            return "";
        }
        String low = firstNonBlank(cfg.getLowComplexityModelName(), "");
        String medium = firstNonBlank(cfg.getMediumComplexityModelName(), "");
        String high = firstNonBlank(cfg.getHighComplexityModelName(), "");
        String fallback = firstNonBlank(cfg.getModelName(), "");

        Complexity complexity = estimateComplexity(goal, context);
        return switch (complexity) {
            // HIGH:优先 high,退路依次 medium → low → fallback
            case HIGH -> firstNonBlank(high, medium, low, fallback);
            // MEDIUM:优先 medium,退路 high(更强但贵)→ low → fallback
            case MEDIUM -> firstNonBlank(medium, high, low, fallback);
            // LOW:优先 low,退路 medium → high → fallback
            case LOW -> firstNonBlank(low, medium, high, fallback);
        };
    }

    /**
     * 默认的 DAG ReAct System Prompt（当配置文件不存在时使用）。
     * Sub-Agent 最大并行数从配置读取（agent.llm.runtime.subAgent.maxCount），不硬编码。
     */
    private String defaultDagReactSystemPrompt() {
        try (java.io.InputStream is = getClass().getResourceAsStream(
                "/prompts/todo/dag_react_system_default.txt")) {
            if (is != null) {
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to load default dag react system prompt from classpath", e);
        }
        log.error("dag_react_system_default.txt not found in classpath; returning empty prompt");
        return "";
    }

    // ─────────────────────────────────────────────────────────────
    // ReAct 统一 System Prompt + Stage Instruction（#28）
    // ─────────────────────────────────────────────────────────────

    /**
     * 统一的 ReAct System Prompt —— 仅包含全局指令，所有阶段共享。
     *
     * <p>Stage-specific 指令改为通过 {@code stageInstruction()} 方法注入到 User Message，
     * 使得 System Prompt 在整个 Agent Run 生命周期内保持字节级一致，
     * 最大化 Fireworks / OpenRouter 的 KV 前缀缓存命中率。</p>
     *
     * @see #planningAnalysisStageInstruction(String, int)
     * @see #planningStructuredStageInstruction()
     * @see #recoveryStageInstruction()
     * @see #finalAnswerStageInstruction()
     */
    public String reactSystemPrompt() {
        return firstNonBlank(currentPrompts().getAgentRunSystemPrompt(), "");
    }

    /**
     * 规划分析阶段指令 —— 注入到 User Message，引导 LLM 先输出自然语言分析。
     * @deprecated 使用 {@link #planningStrategyStageInstruction(String, int, int)} 替代
     */
    @Deprecated
    public String planningAnalysisStageInstruction(String toolWhitelist, int maxTodos) {
        String template = firstNonBlank(
                currentPrompts().getTodoPlannerSystemPromptTemplate(),
                """
                你是任务规划专家。请把用户目标拆解为 Todo List。
                规则:
                1) 只能使用工具: {{toolWhitelist}}
                2) 步骤数必须尽可能少——只拆解真正必要的步骤，能合并则合并，绝不因为"上限允许"就多加步骤；步骤数硬性上限为 {{maxTodos}}，但目标是远少于上限
                3) type 仅允许 TOOL_CALL/SUB_AGENT/THOUGHT
                4) executionMode 仅允许 AUTO/FORCE_SIMPLE/FORCE_SUB_AGENT
                """
        );
        String rendered = render(template, Map.of(
                "toolWhitelist", safe(toolWhitelist),
                "maxTodos", String.valueOf(maxTodos)
        ));
        return "[Stage: PLANNING_ANALYSIS]\n" + rendered
                + "\n请先用自然语言分析用户需求和执行思路，暂时不要输出 JSON。";
    }

    /**
     * 第一阶段：统筹规划阶段指令（结构化输出）。
     * 从配置文件加载: prompts/todo/planning_strategy_stage.txt
     */
    public String planningStrategyStageInstruction(String toolWhitelist, int maxTodos, int maxDetailLength) {
        String template = firstNonBlank(
                currentPrompts().getPlanningStrategyStage(),
                loadPromptFileFromClasspath("prompts/todo/planning_strategy_stage.txt")
        );
        return render(template, Map.of(
                "toolWhitelist", safe(toolWhitelist),
                "maxTodos", String.valueOf(maxTodos),
                "strategyMaxDetailLength", String.valueOf(maxDetailLength),
                "toolCapabilities", buildToolCapabilities(toolWhitelist)
        ));
    }

    /**
     * 构建工具能力说明，帮助规划模型了解工具的批量操作能力。
     *
     * <p>为白名单中每个工具生成一行中文说明,重点说明是否支持批量(逗号分隔多个 ts_code)等关键能力。
     * 工具说明覆盖行情类、检索类、Python 沙箱、RAG 等所有当前可用的工具。</p>
     */
    private String buildToolCapabilities(String toolWhitelist) {
        List<String> tools = List.of(toolWhitelist.split(","));
        List<String> capabilities = new ArrayList<>();

        for (String tool : tools) {
            tool = tool.trim();
            switch (tool) {
                case "getIndexDaily" -> capabilities.add(
                    "- getIndexDaily: 批量查询指数日线数据。支持同时查询多个指数（ts_code 用逗号分隔），建议优先使用批量查询而非多次单查。");
                case "getStockDaily" -> capabilities.add(
                    "- getStockDaily: 批量查询股票日线数据。支持同时查询多只股票（ts_code 用逗号分隔）。");
                case "getFundDaily" -> capabilities.add(
                    "- getFundDaily: 批量查询基金日线数据。支持同时查询多只基金（ts_code 用逗号分隔）。");
                case "searchIndex" -> capabilities.add(
                    "- searchIndex: 搜索指数代码。返回指数名称和代码对应关系。");
                case "searchStock" -> capabilities.add(
                    "- searchStock: 搜索股票代码。返回股票名称和代码对应关系。");
                case "searchFund" -> capabilities.add(
                    "- searchFund: 搜索基金代码。返回基金名称和代码对应关系。");
                case "executePython" -> capabilities.add(
                    "- executePython: 执行 Python 代码进行数据分析。支持批量处理多个数据集（dataset_ids 用逗号分隔）。");
                case "getIndexInfo" -> capabilities.add(
                    "- getIndexInfo: 查询指数基本信息。支持批量查询多个指数。");
                case "getStockInfo" -> capabilities.add(
                    "- getStockInfo: 查询股票基本信息。支持批量查询多只股票。");
                case "getFinancialReport" -> capabilities.add(
                    "- getFinancialReport: 查询财务报表数据（利润表、资产负债表、现金流量表）。");
                case "ragSearch" -> capabilities.add(
                    "- ragSearch: RAG语义检索，查询公告、研报、年报原文内容。");
                case "loadDocument" -> capabilities.add(
                    "- loadDocument: 加载文档进行向量化检索。");
                // 未知工具:仅以名字占位,避免漏掉
                default -> capabilities.add("- " + tool + ": 可用工具");
            }
        }

        return String.join("\n", capabilities);
    }

    /**
     * 第二阶段：任务拆解阶段指令（结构化输出）。
     * 从配置文件加载: prompts/todo/planning_todos_stage.txt
     *
     * <p>会根据 overallPlan.mode() 注入不同的 mode guidance:DAG 模式提示通过 dependsOn 表达依赖,
     * LINEAR 模式提示按 sequence 顺序执行。</p>
     */
    public String planningTodosStageInstruction(world.willfrog.agent.workflow.StructuredPlanningSupport.OverallPlan overallPlan,
                                                  String toolWhitelist, int maxTodos) {
        String template = firstNonBlank(
                currentPrompts().getPlanningTodosStage(),
                loadPromptFileFromClasspath("prompts/todo/planning_todos_stage.txt")
        );
        String modeGuidance = "DAG".equalsIgnoreCase(overallPlan.mode())
                ? "当前是 DAG 模式，请通过 dependsOn 表达任务依赖关系。"
                : "当前是 LINEAR 模式，按 sequence 顺序执行即可。";

        return render(template, Map.of(
                "mode", overallPlan.mode(),
                "detail", overallPlan.detail(),
                "modeGuidance", modeGuidance,
                "toolWhitelist", safe(toolWhitelist),
                "maxTodos", String.valueOf(maxTodos)
        ));
    }

    /**
     * 从 classpath 加载 prompt 文件。
     * 文件不存在或读取失败时返回空串,避免抛异常打断 prompt 拼接。
     */
    private String loadPromptFileFromClasspath(String path) {
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is != null) {
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to load prompt file from classpath: {}", path, e);
        }
        return "";
    }

    /**
     * 规划结构化转换阶段指令 —— 引导 LLM 将自然语言分析转为简化的 Todo JSON。
     *
     * <p>ReAct 模式下，Todo 只包含简短描述，具体工具调用由执行阶段的 LLM 自主决策。</p>
     */
    public String planningStructuredStageInstruction() {
        return "[Stage: PLANNING_STRUCTURED]\n"
                + "请将上述分析转化为简化的 Todo List JSON，只输出 JSON，不要包含其他文字。\n"
                + "\n"
                + "格式示例：\n"
                + "{\"analysis\":\"分析摘要\",\"items\":["
                + "{\"id\":\"todo_1\",\"sequence\":1,\"description\":\"查询贵州茅台的股票代码\","
                + "\"dependsOn\":[]},"
                + "{\"id\":\"todo_2\",\"sequence\":2,\"description\":\"获取茅台2025年的日线数据\","
                + "\"dependsOn\":[\"todo_1\"]},"
                + "{\"id\":\"todo_3\",\"sequence\":3,\"description\":\"分析数据并回答用户关于涨跌幅的问题\","
                + "\"dependsOn\":[\"todo_2\"]}]}\n"
                + "\n"
                + "注意：\n"
                + "1. 每个 Todo 只需要 description 字段（1-3句话描述任务）\n"
                + "2. 不要包含 toolName、params 等具体执行细节\n"
                + "3. DAG 模式下可选 dependsOn 指定依赖关系（默认空数组）\n"
                + "4. Todo 的具体执行将由 ReAct Agent 在执行时自主决策";
    }

    /**
     * Recovery 阶段指令 —— 注入到 User Message，引导 LLM 生成恢复参数。
     */
    public String recoveryStageInstruction() {
        String specific = firstNonBlank(
                currentPrompts().getWorkflowTodoRecoverySystemPrompt(),
                ""
        );
        return "[Stage: TODO_RECOVERY]\n" + specific;
    }

    /**
     * Final Answer 阶段指令 —— 注入到 User Message，引导 LLM 生成最终回答。
     */
    public String finalAnswerStageInstruction() {
        String specific = firstNonBlank(
                currentPrompts().getWorkflowFinalSystemPrompt(),
                currentPrompts().getParallelFinalSystemPrompt(),
                ""
        );
        return "[Stage: FINAL_ANSWER]\n" + specific;
    }

    /**
     * Plan Judge 阶段指令 —— 注入到 User Message，引导 LLM 做出失败判断决策。
     *
     * <p>在 ReAct 累积模式下，Judge 不再使用独立的 System Prompt，
     * 而是通过此阶段指令注入到对话上下文中，共享同一个 System Prompt 前缀。</p>
     */
    public String planJudgeStageInstruction() {
        String specific = firstNonBlank(
                currentPrompts().getPlanJudgeRuntimeSystemPromptTemplate(),
                currentPrompts().getPlanJudgeSystemPromptTemplate(),
                ""
        );
        return "[Stage: PLAN_JUDGE]\n" + specific;
    }

    /**
     * Patch Planner 阶段指令 —— 注入到 User Message，引导 LLM 生成计划补丁。
     *
     * <p>在 ReAct 累积模式下，Patch Planner 不再使用独立的 System Prompt，
     * 而是通过此阶段指令注入到对话上下文中，共享同一个 System Prompt 前缀。</p>
     */
    public String patchPlannerStageInstruction() {
        String specific = firstNonBlank(
                currentPrompts().getParallelPatchPlannerSystemPromptTemplate(),
                ""
        );
        return "[Stage: PATCH_PLAN]\n" + specific;
    }

    /**
     * 组合系统 Prompt（Cache 优化版本）。
     *
     * <p>Prompt 完全静态化：仅包含不变的全局指令 + 角色/任务指令，
     * 日期等动态内容由调用方通过 {@link #dynamicContextPrefix()} 注入到 User Message，
     * 实现 System Prompt 字节级一致，最大化 LLM provider 的 Prompt Caching 命中率。</p>
     *
     * <pre>
     * ┌────────────────────────────────────┐
     * │ [完全静态 - 100% 可缓存]            │
     * │ ├── 全局系统指令 (global)           │
     * │ └── 角色/任务指令 (specific)        │
     * └────────────────────────────────────┘
     * </pre>
     *
     * @see #dynamicContextPrefix()
     */
    private String composeSystemPrompt(String specificPrompt) {
        String global = firstNonBlank(currentPrompts().getAgentRunSystemPrompt(), "");
        String specific = firstNonBlank(specificPrompt, "");

        List<String> parts = new ArrayList<>();
        if (!global.isBlank()) {
            parts.add(global);
        }
        // 跳过与全局完全相同的 specific,避免重复拼接
        if (!specific.isBlank() && !specific.equals(global)) {
            parts.add(specific);
        }
        return String.join("\n", parts).trim();
    }

    /**
     * 合并热加载配置与静态配置,得到最终生效的 Prompts。
     *
     * <p>合并策略:本地热加载非空则用本地,否则用静态。每个字段独立合并,
     * 允许只热更新部分 prompt 字段。</p>
     *
     * @return 合并后的 Prompts 对象(永不为 null)
     */
    private AgentLlmProperties.Prompts currentPrompts() {
        AgentLlmProperties.Prompts base = properties.getPrompts() == null
                ? new AgentLlmProperties.Prompts()
                : properties.getPrompts();
        AgentLlmProperties.Prompts local = localConfigLoader.current()
                .map(AgentLlmProperties::getPrompts)
                .orElse(null);
        if (local == null) {
            return base;
        }
        // 逐字段合并:本地非空优先
        AgentLlmProperties.Prompts merged = new AgentLlmProperties.Prompts();
        merged.setAgentRunSystemPrompt(firstNonBlank(local.getAgentRunSystemPrompt(), base.getAgentRunSystemPrompt()));
        merged.setTodoPlannerSystemPromptTemplate(firstNonBlank(local.getTodoPlannerSystemPromptTemplate(), base.getTodoPlannerSystemPromptTemplate()));
        merged.setWorkflowFinalSystemPrompt(firstNonBlank(local.getWorkflowFinalSystemPrompt(), base.getWorkflowFinalSystemPrompt()));
        merged.setWorkflowTodoRecoverySystemPrompt(firstNonBlank(local.getWorkflowTodoRecoverySystemPrompt(), base.getWorkflowTodoRecoverySystemPrompt()));
        merged.setParallelPlannerSystemPromptTemplate(firstNonBlank(local.getParallelPlannerSystemPromptTemplate(), base.getParallelPlannerSystemPromptTemplate()));
        merged.setParallelFinalSystemPrompt(firstNonBlank(local.getParallelFinalSystemPrompt(), base.getParallelFinalSystemPrompt()));
        merged.setParallelPatchPlannerSystemPromptTemplate(firstNonBlank(local.getParallelPatchPlannerSystemPromptTemplate(), base.getParallelPatchPlannerSystemPromptTemplate()));
        merged.setPlanJudgeSystemPromptTemplate(firstNonBlank(local.getPlanJudgeSystemPromptTemplate(), base.getPlanJudgeSystemPromptTemplate()));
        merged.setPlanJudgeRuntimeSystemPromptTemplate(firstNonBlank(local.getPlanJudgeRuntimeSystemPromptTemplate(), base.getPlanJudgeRuntimeSystemPromptTemplate()));
        merged.setSemanticJudgeSystemPromptTemplate(firstNonBlank(local.getSemanticJudgeSystemPromptTemplate(), base.getSemanticJudgeSystemPromptTemplate()));
        merged.setSubAgentPlannerSystemPromptTemplate(firstNonBlank(local.getSubAgentPlannerSystemPromptTemplate(), base.getSubAgentPlannerSystemPromptTemplate()));
        merged.setSubAgentSummarySystemPrompt(firstNonBlank(local.getSubAgentSummarySystemPrompt(), base.getSubAgentSummarySystemPrompt()));
        merged.setPythonRefineSystemPrompt(firstNonBlank(local.getPythonRefineSystemPrompt(), base.getPythonRefineSystemPrompt()));
        merged.setPythonRefineOutputInstruction(firstNonBlank(local.getPythonRefineOutputInstruction(), base.getPythonRefineOutputInstruction()));
        merged.setOrchestratorPlanningSystemPrompt(firstNonBlank(local.getOrchestratorPlanningSystemPrompt(), base.getOrchestratorPlanningSystemPrompt()));
        merged.setOrchestratorSummarySystemPrompt(firstNonBlank(local.getOrchestratorSummarySystemPrompt(), base.getOrchestratorSummarySystemPrompt()));
        merged.setPythonRefineRequirements(selectList(local.getPythonRefineRequirements(), base.getPythonRefineRequirements()));
        merged.setDatasetFieldSpecs(selectList(local.getDatasetFieldSpecs(), base.getDatasetFieldSpecs()));
        merged.setDagReactSystemPrompt(firstNonBlank(local.getDagReactSystemPrompt(), base.getDagReactSystemPrompt()));
        merged.setDagReactSystemPromptFile(firstNonBlank(local.getDagReactSystemPromptFile(), base.getDagReactSystemPromptFile()));
        merged.setDagModeGuidancePrompt(firstNonBlank(local.getDagModeGuidancePrompt(), base.getDagModeGuidancePrompt()));
        merged.setDagModeGuidancePromptFile(firstNonBlank(local.getDagModeGuidancePromptFile(), base.getDagModeGuidancePromptFile()));
        merged.setPlanningStrategyStageFile(firstNonBlank(local.getPlanningStrategyStageFile(), base.getPlanningStrategyStageFile()));
        merged.setPlanningStrategyStage(firstNonBlank(local.getPlanningStrategyStage(), base.getPlanningStrategyStage()));
        merged.setPlanningTodosStageFile(firstNonBlank(local.getPlanningTodosStageFile(), base.getPlanningTodosStageFile()));
        merged.setPlanningTodosStage(firstNonBlank(local.getPlanningTodosStage(), base.getPlanningTodosStage()));
        return merged;
    }

    /**
     * 合并热加载与静态的 SubAgent 配置。
     * 与 {@link #currentPrompts()} 同样的字段级 fallback 策略。
     */
    private AgentLlmProperties.SubAgent currentSubAgentConfig() {
        AgentLlmProperties.SubAgent base = properties.getRuntime() == null
                ? null
                : properties.getRuntime().getSubAgent();
        AgentLlmProperties.SubAgent local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getSubAgent)
                .orElse(null);
        if (local == null) {
            return base;
        }
        AgentLlmProperties.SubAgent merged = new AgentLlmProperties.SubAgent();
        merged.setEnabled(firstNonNull(local.getEnabled(), base == null ? null : base.getEnabled()));
        merged.setComplexityThreshold(firstNonBlank(local.getComplexityThreshold(), base == null ? null : base.getComplexityThreshold()));
        merged.setMaxSteps(firstNonNull(local.getMaxSteps(), base == null ? null : base.getMaxSteps()));
        merged.setMaxCount(firstNonNull(local.getMaxCount(), base == null ? null : base.getMaxCount()));
        merged.setEndpointName(firstNonBlank(local.getEndpointName(), base == null ? null : base.getEndpointName()));
        merged.setModelName(firstNonBlank(local.getModelName(), base == null ? null : base.getModelName()));
        merged.setLowComplexityModelName(firstNonBlank(local.getLowComplexityModelName(), base == null ? null : base.getLowComplexityModelName()));
        merged.setMediumComplexityModelName(firstNonBlank(local.getMediumComplexityModelName(), base == null ? null : base.getMediumComplexityModelName()));
        merged.setHighComplexityModelName(firstNonBlank(local.getHighComplexityModelName(), base == null ? null : base.getHighComplexityModelName()));
        return merged;
    }

    /**
     * 基于启发式特征估算任务复杂度。
     *
     * <p>规则(每命中一项 score +1):</p>
     * <ul>
     *   <li>文本长度 > 180:复杂度 +1</li>
     *   <li>文本长度 > 360:再 +1(累计可 +2)</li>
     *   <li>含"并行 / parallel / 多个 / multi"等并行词:+1</li>
     *   <li>含"组合 / 回测 / 夏普 / 最大回撤"等量化分析词:+1</li>
     *   <li>含"并且 / 同时 / 另外 / 此外"等多任务连接词:+1</li>
     * </ul>
     *
     * <p>阈值:score ≥ 4 → HIGH;≥ 2 → MEDIUM;否则 LOW。</p>
     */
    private Complexity estimateComplexity(String goal, String context) {
        String text = (safe(goal) + "\n" + safe(context)).toLowerCase(Locale.ROOT);
        int score = 0;
        if (text.length() > 180) {
            score++;
        }
        if (text.length() > 360) {
            score++;
        }
        if (text.contains("并行") || text.contains("parallel") || text.contains("多个") || text.contains("multi")) {
            score++;
        }
        if (text.contains("组合") || text.contains("回测") || text.contains("夏普") || text.contains("最大回撤")) {
            score++;
        }
        if (text.contains("并且") || text.contains("同时") || text.contains("另外") || text.contains("此外")) {
            score++;
        }
        if (score >= 4) {
            return Complexity.HIGH;
        }
        if (score >= 2) {
            return Complexity.MEDIUM;
        }
        return Complexity.LOW;
    }

    /** 返回 first 非空时取 first,否则取 second。用于字段级合并。 */
    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    /** 复杂度三档,用于驱动 Sub-Agent 模型选择。 */
    private enum Complexity {
        LOW,
        MEDIUM,
        HIGH
    }

    /**
     * 简易模板渲染:把 {@code {{key}}} 占位符替换为 vars 中的值。
     * 模板为 null 时返回空串。
     */
    private String render(String template, Map<String, String> vars) {
        String out = safe(template);
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            out = out.replace("{{" + entry.getKey() + "}}", safe(entry.getValue()));
        }
        return out;
    }

    /**
     * List 选择:local 非空则选 local,否则选 base。
     * 不做字段级合并,因为列表类配置通常是整体替换。
     */
    private <T> List<T> selectList(List<T> local, List<T> base) {
        if (local != null && !local.isEmpty()) {
            return local;
        }
        return base == null ? List.of() : base;
    }

    /**
     * 返回第一个非空白字符串(已 trim)。
     * 全部为空时返回空串。
     */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    /** 空安全:null 转为空串。 */
    private String safe(String text) {
        return text == null ? "" : text;
    }
}

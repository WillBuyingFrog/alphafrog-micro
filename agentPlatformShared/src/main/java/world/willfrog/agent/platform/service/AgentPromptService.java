package world.willfrog.agent.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Agent 提示词中心 —— 整个 agent 系统所有 LLM 调用的 prompt 都在这里组装，
 * 是面试理解 agent 全流程最关键的三个文件之一
 * （另两个是 {@code LangchainLinearRunPipelineImpl} 和 {@code LangchainAiPlanner}）。
 *
 * <h2>这个类解决什么问题</h2>
 * <p>agent 在不同阶段（planning 规划 / execution 执行 / final answer 最终回答 / recovery 恢复 / judge 判定）
 * 需要给 LLM 发送不同的 system prompt 和 user message。
 * 所有 prompt 的加载优先级、时间基准注入、阶段指令拼装、工具能力说明生成都集中在这里，避免散落在各处导致不一致。</p>
 *
 * <h2>Prompt 的三层结构</h2>
 * <ol>
 *   <li><b>时间基准前缀</b>（harness，由 {@link #composeSystemPrompt(String)} 动态注入）：
 *       每次调用都根据 {@code LocalDate.now()} 计算当前年月日 + 相对年份映射规则。
 *       例如 "当前时间：2026年05月25日（星期一，2026年5月25日）...说去年，指2025年"。
 *       这是防止 LLM 把"去年"推理成错误年份的兜底机制。</li>
 *   <li><b>全局 agent 指令</b>（global，来自 {@code agentRunSystemPrompt}）：
 *       所有阶段共享的基础角色定义和约束（如"你是专业金融分析代理"、禁止猜测代码、必须用具体年份等）。
 *       在 System Message 中保持不变，利于 OpenAI 兼容 API 的 KV 前缀缓存（prompt caching）。</li>
 *   <li><b>阶段专属指令</b>（stage-specific，来自各 {@code stageInstruction()} 方法）：
 *       注入到 User Message 而非 System Message，
 *       引导 LLM 在当前阶段完成特定任务（如 strategy 统筹规划、todos 任务拆解、final answer 汇总）。
 *       放在 User Message 的设计意图是保持 System Prompt 稳定以提升缓存命中率。</li>
 * </ol>
 *
 * <h2>配置加载的三级优先级</h2>
 * <ol>
 *   <li><b>Nacos 热加载</b>：{@code agent-llm.local.json}，通过 {@link AgentLlmLocalConfigLoader} 10s 轮询，
 *       改配置后无需重启服务即可生效。 </li>
 *   <li><b>application.yml 静态配置</b>：Spring Boot 标准配置，作为热加载不可用时的 fallback（回退）。</li>
 *   <li><b>classpath 内置默认文件</b>：如 {@code prompts/todo/dag_react_system_default.txt}，
 *       Jar 包内自带，确保最差情况下仍有可用的 prompt。</li>
 * </ol>
 * 所有 prompt 字段通过 {@link #currentPrompts()} 按以上优先级逐字段合并，本地非空则覆盖静态。
 *
 * <h2>与规划（planning）和执行（execution）的协作关系</h2>
 * <ul>
 *   <li>{@link #reactSystemPrompt()} → 用于两阶段 planning 的 System Message，
 *       {@link world.willfrog.agentlangchain.planning.LangchainAiPlanner} 调用。</li>
 *   <li>{@link #dagReactSystemPrompt()} → 用于 DAG 节点执行（单 todo 的 ReAct 循环）的 System Message，
 *       {@code LangchainTodoNodeExecutor} 调用。</li>
 *   <li>{@link #planningStrategyStageInstruction(String, int, int)} → 规划第一阶段（统筹）的 User Message 指令，
 *       含 {@link #buildToolCapabilities(String)} 生成的工具能力清单。</li>
 *   <li>{@link #planningTodosStageInstruction(String, String, String, int)} → 规划第二阶段（任务拆解）的 User Message 指令。</li>
 *   <li>{@link #composeSystemPrompt(String)} → 所有 System Prompt 的统一入口，注入时间基准 + 全局指令。</li>
 * </ul>
 *
 * <h2>Sub-Agent 模型选择</h2>
 * <p>{@link #selectSubAgentModelName(String, String)} 根据任务目标的文本长度和关键词做启发式复杂度估算
 * （{@link Complexity#LOW}/{@link Complexity#MEDIUM}/{@link Complexity#HIGH}），
 * 然后从配置中选择对应档位的模型名，高复杂度用强模型（如 GPT-5.4），低复杂度用便宜模型（如 Qwen 122B），
 * 兼顾效果和成本。</p>
 *
 * <h2>面试可能被问到的点</h2>
 * <ul>
 *   <li>"System Prompt 为什么按天变化？不会破坏 KV 缓存吗？"
 *       → 时间基准确实会让 system prompt 每天不同，降低跨日缓存命中率。但我们优先保证时间推理正确
 *       （否则 LLM 会把"去年"算成 2024 而非 2025），缓存损失可接受。</li>
 *   <li>"prompt 怎么热更新？"
 *       → Nacos 推送 agent-llm.local.json → AgentLlmLocalConfigLoader 10s 轮询 →
 *       currentPrompts() 逐字段合并 → 下次 LLM 调用自动使用新 prompt。全程不重启。</li>
 *   <li>"checkParallelLimits 为什么放在 prompt 里引导而不是硬编码？"
 *       → 批量上限是运行时配置（可热改），硬编码在 prompt 里会导致配置改了但 LLM 仍按旧数字发请求。
 *       引导 LLM 先调 checkParallelLimits 获取当前上限，配置变更时 prompt 无需改动。</li>
 * </ul>
 *
 * @see AgentLlmLocalConfigLoader 热加载配置加载器
 * @see AgentLlmProperties 配置结构定义
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentPromptService {

    /** 中文日期格式化器，"yyyy年MM月dd日"，如 "2026年05月25日"。
     *  用于 {@link #dynamicContextPrefix()} 和 {@link #composeSystemPrompt(String)} 中的日期展示。 */
    private static final DateTimeFormatter CN_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日");
    /** Spring Boot 静态配置（application.yml / application-agent-llm-prompts.yml），
     *  作为 Nacos 热加载不可用时的 fallback。 */
    private final AgentLlmProperties properties;
    /** Nacos 热加载配置（agent-llm.local.json），10s 轮询，支持不重启更新 prompt。
     *  优先级高于 {@link #properties}。 */
    private final AgentLlmLocalConfigLoader localConfigLoader;

    /**
     * 返回 Agent Run 入口的完整 System Prompt（含时间基准 + 全局指令）。
     * 主要供旧版调用方（如 legacy agentService）使用；新代码（agentLangchainService）
     * 倾向使用 {@link #reactSystemPrompt()} 配合 stage instruction 注入到 User Message。
     *
     * @return 时间基准 + agent_run_system.txt 全局指令的拼接结果
     */
    public String agentRunSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getAgentRunSystemPrompt(), ""));
    }

    /**
     * Todo Planner 的 System Prompt（用于让 LLM 把用户目标拆解为 Todo List）。
     *
     * <p>模板中的 {@code {{toolWhitelist}}} 和 {@code {{maxTodos}}} 占位符会被替换为实际值。
     * 若 Nacos 和 application.yml 都未配置模板，使用内置默认值（包含强约束 JSON 输出格式）。
     * 最终会经过 {@link #composeSystemPrompt(String)} 注入时间基准。</p>
     *
     * @param toolWhitelist 可用工具名列表，逗号分隔，如 "searchIndex,getIndexDaily,executePython"
     * @param maxTodos      总步骤数上限
     * @return 拼接时间基准 + 全局指令 + 阶段指令后的完整 System Prompt
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
     * 返回需要由调用方注入到 User Message 的动态上下文前缀。
     *
     * <p>生成 "今天是2026年05月25日。" 格式的日期提示，作为 User Message 的<b>第二时间锚点</b>。
     * 虽然 {@link #composeSystemPrompt(String)} 已经注入了时间基准，
     * 但在 User Message 中再重复一次可以增强 LLM 对当前日期的感知。</p>
     *
     * <p>典型用法：</p>
     * <pre>{@code
     * String systemPrompt = promptService.reactSystemPrompt();
     * String dynamicPrefix = promptService.dynamicContextPrefix();
     * // User Message = 日期前缀 + 阶段指令 + 用户目标
     * String userMsg = dynamicPrefix + "\n" + stageInstruction + "\n\n用户需求：" + userGoal;
     * }</pre>
     *
     * @return "今天是yyyy年MM月dd日。" 格式的日期字符串
     */
    public String dynamicContextPrefix() {
        return "今天是" + LocalDate.now().format(CN_DATE_FORMATTER) + "。";
    }

    /**
     * 工作流最终回答阶段的 System Prompt。
     * 兼容 {@code workflowFinalSystemPrompt} 与历史的 {@code parallelFinalSystemPrompt} 命名。
     *
     * @return 含时间基准的 final answer 阶段 System Prompt
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
     * 当某个 Todo 执行失败后，LLM 根据此 prompt 生成可重试的恢复参数（如换关键词、调整日期范围等）。
     *
     * @return 含时间基准的 recovery 阶段 System Prompt
     */
    public String workflowTodoRecoverySystemPrompt() {
        return composeSystemPrompt(firstNonBlank(
                currentPrompts().getWorkflowTodoRecoverySystemPrompt(),
                ""
        ));
    }

    /**
     * 并行规划器 System Prompt（便捷重载）。
     * candidateIndex 和 candidateCount 均默认 1（即单候选方案）。
     * 当需要生成多个候选 Plan 时使用带 candidate 参数的完整重载版本。
     *
     * @param toolWhitelist    可用工具白名单
     * @param maxTasks         总任务数上限
     * @param maxSubSteps      单任务子步骤上限
     * @param maxParallelTasks 并行任务数上限
     * @param maxSubAgents     Sub-Agent（子代理）数量上限
     */
    public String parallelPlannerSystemPrompt(String toolWhitelist,
                                              int maxTasks,
                                              int maxSubSteps,
                                              int maxParallelTasks,
                                              int maxSubAgents) {
        return parallelPlannerSystemPrompt(toolWhitelist, maxTasks, maxSubSteps, maxParallelTasks, maxSubAgents, 1, 1);
    }

    /**
     * 并行规划器 System Prompt（完整版，带候选方案配置）。
     *
     * <p>当一次 agent run 需要同时生成多个并行候选方案（如 multi-plan 模式）时，
     * {@code candidateIndex} / {@code candidateCount} 用于告诉 LLM "你是第几个候选"，
     * 促使不同候选生成有差异的 Plan 而非相互重复。</p>
     *
     * @param toolWhitelist    可用工具白名单
     * @param maxTasks         总任务数上限
     * @param maxSubSteps      单任务子步骤上限
     * @param maxParallelTasks 并行任务数上限
     * @param maxSubAgents     Sub-Agent 数量上限
     * @param candidateIndex   当前候选序号（从 1 开始），至少为 1
     * @param candidateCount   候选方案总数，至少为 1
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
                // candidateIndex/candidateCount 至少为 1，避免渲染出 "第 0/0 个候选" 这种无意义文案
                "candidateIndex", String.valueOf(Math.max(candidateIndex, 1)),
                "candidateCount", String.valueOf(Math.max(candidateCount, 1))
        ));
        return composeSystemPrompt(specific);
    }

    /** 并行执行结束后的最终汇总（final answer）System Prompt。 */
    public String parallelFinalSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getParallelFinalSystemPrompt(), ""));
    }

    /** 并行 Plan 补丁（patch）阶段的 System Prompt，用于对已有 Plan 做增量修正。 */
    public String parallelPatchPlannerSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getParallelPatchPlannerSystemPromptTemplate(), ""));
    }

    /** Plan Judge（计划判定器，静态版本）的 System Prompt。
     *  用于让 LLM 判断一个 Plan 是否可执行或需要修正。 */
    public String planJudgeSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getPlanJudgeSystemPromptTemplate(), ""));
    }

    /**
     * Plan Judge 的运行时版本 System Prompt。
     *
     * <p>优先使用 {@code planJudgeRuntimeSystemPromptTemplate}（更适合运行时动态判定场景）；
     * 如果未配置则 fallback 到静态版本 {@link #planJudgeSystemPrompt()}，保证向后兼容。</p>
     *
     * @return 运行时或静态 Plan Judge System Prompt
     */
    public String planJudgeRuntimeSystemPrompt() {
        String runtimePrompt = firstNonBlank(currentPrompts().getPlanJudgeRuntimeSystemPromptTemplate(), "");
        if (!runtimePrompt.isEmpty()) {
            return composeSystemPrompt(runtimePrompt);
        }
        return planJudgeSystemPrompt();
    }

    /** 语义判断器（SemanticJudge）的 System Prompt。
     *  用于让 LLM 对执行结果做语义级别的是否满足用户意图判定。 */
    public String semanticJudgeSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getSemanticJudgeSystemPromptTemplate(), ""));
    }

    /**
     * Sub-Agent（子代理）内部规划器的 System Prompt。
     * Sub-Agent 是主 Agent 在执行阶段派生的独立 LLM 任务，有自己的工具集和步骤限制。
     *
     * @param tools    分配给 Sub-Agent 的可用工具列表，逗号分隔
     * @param maxSteps Sub-Agent 内部最大步骤数
     * @return 含时间基准的 Sub-Agent Planner System Prompt
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

    /** Sub-Agent 完成任务后的内容总结 System Prompt。 */
    public String subAgentSummarySystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getSubAgentSummarySystemPrompt(), ""));
    }

    /** Python 代码自动修正（refine）阶段的 System Prompt。
     *  当 LLM 生成的 Python 代码执行出错时，用此 prompt 引导 LLM 修复代码。 */
    public String pythonRefineSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getPythonRefineSystemPrompt(), ""));
    }

    /**
     * Python refine（代码修正）阶段的硬性需求清单（如必须使用 pandas、必须遍历 sandbox 挂载目录等）。
     * 以条目列表形式注入到 User Message 中，避免 LLM 在修复代码时偏离规范。
     *
     * @return 不可变的需求列表（配置为空时返回空列表）
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

    /** Python refine 阶段的输出格式指令（如"输出 markdown 表格"等）。 */
    public String pythonRefineOutputInstruction() {
        return firstNonBlank(currentPrompts().getPythonRefineOutputInstruction());
    }

    /**
     * 数据集字段说明文档（供 LLM 理解每个数据列的字段名、含义、类型和格式）。
     *
     * <p>从配置的 {@code datasetFieldSpecs} 列表渲染为如下格式：</p>
     * <pre>
     * - trade_date | 含义: 交易日期 | 类型: string | 格式: YYYYMMDD
     * - close      | 含义: 收盘价   | 类型: double | 格式: 保留两位小数
     * </pre>
     *
     * @return 多行字段说明文本，无配置时返回空串
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

    /** Orchestrator（任务编排器）规划阶段的 System Prompt。
     *  用于多 Agent 协作场景下主控 Agent 的规划。 */
    public String orchestratorPlanningSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getOrchestratorPlanningSystemPrompt(), ""));
    }

    /** Orchestrator 总结阶段的 System Prompt。 */
    public String orchestratorSummarySystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getOrchestratorSummarySystemPrompt(), ""));
    }

    /**
     * DAG ReAct 执行阶段的 System Prompt —— 单个 todo 节点在 DAG 模式下执行时使用。
     *
     * <p><b>业务定位</b>：这是执行阶段（而非规划阶段）的 System Prompt。
     * 每个 DAG todo 节点在执行时会构造以这个 prompt 为 System Message 的 LC4j AiServices 对话，
     * LLM 在 ReAct（思考-行动-观察）循环中逐步调用工具完成任务。
     * 它回答的是"作为一个被分配了具体任务的节点，你该怎么一步一步完成它"，
     * 与 {@link #reactSystemPrompt()} 回答的"全局角色是什么"是不同的层次。</p>
     *
     * <p><b>加载优先级</b>（三级 fallback）：</p>
     * <ol>
     *   <li>{@code agent.llm.prompts.dagReactSystemPrompt} — Nacos / application.yml 直接配置的文本内容</li>
     *   <li>{@code agent.llm.prompts.dagReactSystemPromptFile} —
     *       通过 {@code file:prompts/todo/dag_react_system.txt} 引用外部文件，
     *       由 {@link AgentLlmLocalConfigLoader} 解析为文本内容</li>
     *   <li>classpath 内置默认文件 {@code prompts/todo/dag_react_system_default.txt} — Jar 包自带兜底</li>
     * </ol>
     *
     * <p><b>与 planning 一致</b>：通过 {@link #composeSystemPrompt(String)} 注入时间基准前缀与全局 agent_run 指令，
     * 保证执行阶段也能正确推理"去年是2025年"。这是 2026-05-25 commit 691cd51 修复的 harness bug——
     * 此前 dagReactSystemPrompt 没有走 composeSystemPrompt，导致执行阶段 LLM 缺少时间上下文。</p>
     *
     * @return 含时间基准 + 全局指令 + DAG 执行专用指令的 System Prompt
     * @see #reactSystemPrompt() planning 阶段的 System Prompt
     */
    public String dagReactSystemPrompt() {
        String specific = firstNonBlank(
                currentPrompts().getDagReactSystemPrompt(),
                currentPrompts().getDagReactSystemPromptFile(),
                defaultDagReactSystemPrompt()
        );
        return composeSystemPrompt(specific);
    }

    /**
     * DAG 模式引导提示（用于 planning 阶段）。
     * 告诉规划器当前使用 DAG 模式，需要通过 dependsOn（依赖声明）表达任务之间的依赖关系。
     *
     * <p>加载优先级：Nacos 直接配置内容 ＞ Nacos 文件引用 ＞ 空串（不引导）。</p>
     */
    public String dagModeGuidancePrompt() {
        return firstNonBlank(
                currentPrompts().getDagModeGuidancePrompt(),
                currentPrompts().getDagModeGuidancePromptFile(),
                ""
        );
    }

    /**
     * 返回配置的最大并行 Sub-Agent 数量。
     *
     * <p>从 {@code agent.llm.runtime.subAgent.maxCount} 读取，支持热加载。
     * 默认值 3。用于限制一次 agent run 中同时运行的 Sub-Agent 数量上限。</p>
     *
     * @return 最大 Sub-Agent 并行数（≥1）
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
     * Sub-Agent 的 LLM endpoint 名称。
     * 为空时表示沿用主 Agent 的 endpoint（即不单独指定）。
     *
     * @return endpoint 名称或空串
     */
    public String subAgentEndpointName() {
        AgentLlmProperties.SubAgent cfg = currentSubAgentConfig();
        return cfg == null ? "" : firstNonBlank(cfg.getEndpointName(), "");
    }

    /**
     * 根据任务目标复杂度选择 Sub-Agent 的模型名称。
     *
     * <p><b>为什么需要这个</b>：Sub-Agent 处理的任务复杂度差异很大——
     * 简单的如"搜一个股票代码"用便宜的模型（如 Qwen 122B），
     * 复杂的如"分析多资产回测结果"用强模型（如 GPT-5.4），
     * 通过启发式估算自动选择可以兼顾效果和成本。</p>
     *
     * <p>选择流程：</p>
     * <ol>
     *   <li>{@link #estimateComplexity(String, String)} 根据文本长度 + 关键词特征估算 LOW / MEDIUM / HIGH</li>
     *   <li>按估算结果取对应级别的模型名</li>
     *   <li>若对应级别未配置，按 HIGH → MEDIUM → LOW → fallback（兜底模型）顺序逐级回退，确保最终返回非空</li>
     * </ol>
     *
     * @param goal    Sub-Agent 的任务目标描述
     * @param context 补充上下文（如依赖 todo 的输出摘要）
     * @return 选定的模型名称（可能为空串表示沿用主 Agent 模型）
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
            // HIGH: 优先 high → 回退 medium → low → fallback（一次性找最强可用模型）
            case HIGH -> firstNonBlank(high, medium, low, fallback);
            // MEDIUM: 优先 medium → 回退 high（贵但强）→ low → fallback
            case MEDIUM -> firstNonBlank(medium, high, low, fallback);
            // LOW: 优先 low（便宜）→ 回退 medium → high → fallback
            case LOW -> firstNonBlank(low, medium, high, fallback);
        };
    }

    /**
     * 从 classpath 加载默认的 DAG ReAct System Prompt（Jar 包内置兜底）。
     * 当 Nacos 和 application.yml 都未配置 dagReactSystemPrompt 时使用。
     *
     * <p>注意：返回的原始文本会再经过 {@link #composeSystemPrompt(String)} 注入时间基准，
     * 最终 LLM 收到的 System Prompt 不止是这个文件的内容。</p>
     *
     * @return 默认 DAG ReAct 文本，classpath 文件不存在时返回空串（此时会记 error 日志）
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
    // ReAct 统一 System Prompt + 各阶段 Stage Instruction（#28 重构）
    //
    // 设计意图：System Prompt 在整个 Agent Run 生命周期中保持不变（只含时间基准 + 全局角色定义），
    // 各阶段的差异指令通过 stageInstruction() 注入到 User Message。
    // 这样做的好处：System Prompt 内容稳定 → OpenAI 兼容 API 的自动 KV 前缀缓存命中率更高 →
    // 减少 LLM 调用延迟和 token 消耗。
    // ─────────────────────────────────────────────────────────────

    /**
     * 统一的 ReAct System Prompt —— planning / execution / final answer 等所有阶段的共享前缀。
     *
     * <p>内容结构：时间基准 + {@code agentRunSystemPrompt}（全局 agent 角色定义 + 通用约束）。</p>
     *
     * <p>与旧版 {@link #agentRunSystemPrompt()} 的区别：这个方法<b>不加阶段专属 prompt</b>，
     * 阶段差异由调用方通过 stage instruction 注入到 User Message。
     * 这样 System Prompt 在所有阶段中完全相同，利于 LLM 的 prompt 前缀缓存。</p>
     *
     * <p><b>使用方</b>：{@code LangchainAiPlanner.planTwoStageStructured()} 在 strategy 和 todos
     * 两个规划阶段都使用同一个 reactSystemPrompt 作为 System Message。</p>
     *
     * @return "时间基准 + 全局 agent 指令" 的拼接结果
     * @see #dagReactSystemPrompt() DAG 节点执行的 System Prompt
     */
    public String reactSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getAgentRunSystemPrompt(), ""));
    }

    /**
     * 规划分析阶段指令（已废弃）。
     *
     * @param toolWhitelist 可用工具白名单
     * @param maxTodos      最大 todo 步骤数
     * @return 含 [Stage: PLANNING_ANALYSIS] 标记的 User Message 指令
     * @deprecated 使用 {@link #planningStrategyStageInstruction(String, int, int)} 作为第一阶段替代
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
     * 第一阶段：统筹规划（strategy）阶段指令 —— 注入到 User Message。
     *
     * <p><b>在两阶段规划中的角色</b>：这是第一个 LLM 调用的 User Message 中的阶段专属指令。
     * LLM 拿到 System Prompt（时间基准 + 全局指令）+ 这个指令 + 用户原始需求后，
     * 输出一个 {@code overallPlan}，包含 {@code mode}（LINEAR 还是 DAG）
     * 和 {@code detail}（用自然语言描述的高层执行策略，如"先搜 ETF 代码，再批量拉日线，最后 Python 算收益率"）。
     * 这个 detail 会在第二阶段注入到 todo 拆解的指令中，作为上下文传递给 LLM。</p>
     *
     * <p>模板来源：Nacos 配置 ＞ classpath 文件 {@code prompts/todo/planning_strategy_stage.txt}。
     * 模板中可引用 {@code {{toolCapabilities}}} 占位符，
     * 实际值由 {@link #buildToolCapabilities(String)} 动态生成（含 checkParallelLimits 引导）。</p>
     *
     * @param toolWhitelist   可用工具白名单，逗号分隔
     * @param maxTodos        最大 todo 步骤数
     * @param maxDetailLength strategy detail 文本的最大长度（超过会被截断或要求 LLM 压缩）
     * @return 含工具能力清单的 strategy 阶段 User Message 指令
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
     * 构建工具能力说明清单 —— 告诉规划阶段的 LLM 每个工具能做什么、是否支持批量、批量上限是多少。
     *
     * <p><b>设计要点（2026-05-25 PL-A 重构）</b>：</p>
     * <ul>
     *   <li>不再硬编码"单次最多 2 个"或"默认最多 3 个"——
     *       统一引导 LLM 先调用 {@code checkParallelLimits} 工具获取当前生效的批量上限。
     *       这样运行时改了 Nacos 配置后无需改 prompt。</li>
     *   <li>{@code checkParallelLimits} 排在第一位，确保 LLM 优先看到。
     *       同时给出 fallback 规则："如果工具列表中未见 checkParallelLimits 或调用失败，默认不批量"。</li>
     *   <li>每个支持批量的工具（如 getIndexDaily、searchAssetInfo 等）的描述中注明
     *       "具体上限先调用 checkParallelLimits 查询"。</li>
     *   <li>未知工具（白名单中有但 switch 未匹配）以工具名占位，保证不丢失信息。</li>
     * </ul>
     *
     * @param toolWhitelist 可用工具名白名单，逗号分隔（来自 planning 阶段解析出的工具列表）
     * @return 以换行分隔的工具能力说明文本，直接注入到 strategy stage instruction 中
     */
    private String buildToolCapabilities(String toolWhitelist) {
        List<String> tools = List.of(toolWhitelist.split(","));
        List<String> capabilities = new ArrayList<>();

        for (String tool : tools) {
            tool = tool.trim();
            switch (tool) {
                case "checkParallelLimits" -> capabilities.add(
                    "- checkParallelLimits: 查询当前批量/并行查询限制。任何工具批量调用前应先调用它；若工具列表中没有 checkParallelLimits 或调用失败，默认不要批量。");
                case "getIndexDaily" -> capabilities.add(
                    "- getIndexDaily: 查询指数日线数据。tsCode 支持 | 分隔或 JSON 数组批量；具体上限先调用 checkParallelLimits 查询，如 tsCode=\"000300.SH|000905.SH\"；批量返回 data.mode=batch 和 data.results。");
                case "getStockDaily" -> capabilities.add(
                    "- getStockDaily: 查询股票日线数据。tsCode 支持 | 分隔或 JSON 数组批量；具体上限先调用 checkParallelLimits 查询，如 tsCode=\"000001.SZ|600519.SH\"；批量返回 data.mode=batch 和 data.results。");
                case "searchAssetInfo" -> capabilities.add(
                    "- searchAssetInfo: 统一搜索股票/ETF/指数/场外基金。ETF 回测或行业主题 ETF 筛选优先用此工具并设 assetTypes=etf；场外基金用 assetTypes=off_exchange_fund。query 支持 | 分隔或 JSON 数组，具体上限先调用 checkParallelLimits 查询。");
                case "getExchangeAssetDaily" -> capabilities.add(
                    "- getExchangeAssetDaily: 查询场内资产日线（股票/ETF/指数）。tsCode 支持批量，具体上限先调用 checkParallelLimits 查询；ETF 需 A5 服务就绪。");
                case "getOffExchangeAssetDaily" -> capabilities.add(
                    "- getOffExchangeAssetDaily: 查询场外基金净值序列，不用于 ETF 场内回测。");
                case "getListedAssetShareSize" -> capabilities.add(
                    "- getListedAssetShareSize: 查询 ETF 份额规模时序；exchange 使用 SSE/SZSE/BSE。");
                case "getEtfAdj" -> capabilities.add(
                    "- getEtfAdj: 查询 ETF 复权因子；仅当 adjFactorEnabled=true 时可用。");
                case "searchIndex" -> capabilities.add(
                    "- searchIndex: 搜索指数代码。keyword 支持 | 分隔或 JSON 数组批量；具体上限先调用 checkParallelLimits 查询，如 keyword=\"沪深300|中证500\"；批量返回 data.mode=batch 和 data.results。");
                case "searchStock" -> capabilities.add(
                    "- searchStock: 搜索股票代码。keyword 支持 | 分隔或 JSON 数组批量；具体上限先调用 checkParallelLimits 查询，如 keyword=\"平安银行|万科A\"；批量返回 data.mode=batch 和 data.results。");
                case "searchFund" -> capabilities.add(
                    "- searchFund: 仅搜索场外基金（公募基金）基本信息，不用于 ETF 场内资产筛选。keyword 支持 | 分隔或 JSON 数组批量；具体上限先调用 checkParallelLimits 查询，如 keyword=\"易方达蓝筹精选|招商中证白酒\"；批量返回 data.mode=batch 和 data.results。");
                case "executePython" -> capabilities.add(
                    "- executePython: 执行 Python 代码进行数据分析。支持批量处理多个数据集（dataset_ids 用逗号分隔）。");
                case "getIndexInfo" -> capabilities.add(
                    "- getIndexInfo: 查询指数基本信息。tsCode 支持 | 分隔或 JSON 数组批量；具体上限先调用 checkParallelLimits 查询，如 tsCode=\"000300.SH|000905.SH\"。");
                case "getStockInfo" -> capabilities.add(
                    "- getStockInfo: 查询股票基本信息。tsCode 支持 | 分隔或 JSON 数组批量；具体上限先调用 checkParallelLimits 查询，如 tsCode=\"000001.SZ|600519.SH\"。");
                case "getFinancialReport" -> capabilities.add(
                    "- getFinancialReport: 查询财务报表数据（利润表、资产负债表、现金流量表）。");
                case "ragSearch" -> capabilities.add(
                    "- ragSearch: RAG语义检索，查询公告、研报、年报原文内容。");
                case "loadDocument" -> capabilities.add(
                    "- loadDocument: 加载文档进行向量化检索。");
                default -> capabilities.add("- " + tool + ": 可用工具");
            }
        }

        return String.join("\n", capabilities);
    }

    /**
     * 第二阶段：任务拆解（todos）阶段指令 —— 注入到 User Message。
     *
     * <p><b>在两阶段规划中的角色</b>：第一个 LLM 调用产出了 strategy（模式 + 高层策略），
     * 第二个 LLM 调用负责把策略具体化为可执行的 todo list。
     * 这个方法的返回值就是第二个 LLM 调用的 User Message 中的阶段专属指令。</p>
     *
     * <p>注入的关键上下文：</p>
     * <ul>
     *   <li>{@code mode} — "LINEAR" 或 "DAG"，影响 LLM 是否填写 dependsOn 字段</li>
     *   <li>{@code detail} — strategy 阶段产出的高层策略文本，帮助 LLM 理解整体意图</li>
     *   <li>{@code modeGuidance} — 根据 mode 动态生成：DAG 模式提示填写 dependsOn，LINEAR 模式提示按 sequence 顺序</li>
     * </ul>
     *
     * <p>模板来源：Nacos 配置 ＞ classpath 文件 {@code prompts/todo/planning_todos_stage.txt}。</p>
     *
     * @param mode         执行模式 "LINEAR" 或 "DAG"
     * @param detail       strategy 阶段产出的高层策略文本
     * @param toolWhitelist 可用工具白名单
     * @param maxTodos     最大 todo 步骤数
     * @return 含 mode guidance 的 todos 阶段 User Message 指令
     */
    public String planningTodosStageInstruction(String mode, String detail,
                                                  String toolWhitelist, int maxTodos) {
        String template = firstNonBlank(
                currentPrompts().getPlanningTodosStage(),
                loadPromptFileFromClasspath("prompts/todo/planning_todos_stage.txt")
        );
        String modeGuidance = "DAG".equalsIgnoreCase(mode)
                ? "当前是 DAG 模式，请通过 dependsOn 表达任务依赖关系。"
                : "当前是 LINEAR 模式，按 sequence 顺序执行即可。";

        return render(template, Map.of(
                "mode", safe(mode),
                "detail", safe(detail),
                "modeGuidance", modeGuidance,
                "toolWhitelist", safe(toolWhitelist),
                "maxTodos", String.valueOf(maxTodos)
        ));
    }

    /**
     * 从 classpath 安全加载 prompt 文本文件（如 {@code prompts/todo/planning_strategy_stage.txt}）。
     *
     * <p>文件不存在或读取失败时返回空串（warn 级别日志），不会抛异常导致上层调用中断。
     * 这是刻意设计——prompt 缺失不应让整个 agent run 崩溃。</p>
     *
     * @param path classpath 相对路径，如 "prompts/todo/planning_strategy_stage.txt"
     * @return 文件的 UTF-8 文本内容，文件不存在时返回空串
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
     * 规划结构化转换阶段指令 —— 引导 LLM 将自然语言分析转化为简化的 Todo JSON。
     *
     * <p>ReAct 模式下，Todo 只需包含简短描述（description），
     * 具体的工具调用（toolName / params）由执行阶段的 LLM 在 ReAct 循环中自主决策，
     * 规划阶段不预先绑定工具参数。这样每个 todo 执行时拥有最大的灵活性。</p>
     *
     * @return 含 JSON 格式示例和注意事项的 structured 阶段指令
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
     * Recovery（恢复）阶段指令 —— 当某个 Todo 执行失败后，引导 LLM 生成替代方案。
     *
     * <p>例如：用不同的搜索关键词重试、缩小时间范围、换一个工具等。
     * 注入到 User Message 中，System Prompt 仍然保持为 {@link #reactSystemPrompt()}。</p>
     *
     * @return 带 [Stage: TODO_RECOVERY] 标记的 recovery 指令
     */
    public String recoveryStageInstruction() {
        String specific = firstNonBlank(
                currentPrompts().getWorkflowTodoRecoverySystemPrompt(),
                ""
        );
        return "[Stage: TODO_RECOVERY]\n" + specific;
    }

    /**
     * Final Answer（最终回答）阶段指令 —— 所有 todo 执行完毕后，
     * 引导 LLM 汇总中间结果，生成面向用户的最终回答。
     *
     * @return 带 [Stage: FINAL_ANSWER] 标记的 final answer 指令
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
     * Plan Judge（计划判定）阶段指令 —— 引导 LLM 对执行结果做"是否失败、需要重试"的判定。
     *
     * <p>在 ReAct 累积模式下，Judge 不另起独立的 System Prompt，
     * 而是共享主流程的 System Prompt（{@link #reactSystemPrompt()}），
     * 只通过此阶段指令注入到对话上下文来切换任务。</p>
     *
     * @return 带 [Stage: PLAN_JUDGE] 标记的 judge 指令
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
     * Patch Planner（计划补丁）阶段指令 —— 引导 LLM 对执行中出现问题的 Plan 做增量修正，
     * 而非全盘推翻重来。
     *
     * <p>与 {@link #planJudgeStageInstruction()} 一样共享主流程的 System Prompt。</p>
     *
     * @return 带 [Stage: PATCH_PLAN] 标记的 patch 指令
     */
    public String patchPlannerStageInstruction() {
        String specific = firstNonBlank(
                currentPrompts().getParallelPatchPlannerSystemPromptTemplate(),
                ""
        );
        return "[Stage: PATCH_PLAN]\n" + specific;
    }

    /**
     * 组装完整的 System Prompt：<b>时间基准 + 全局 agent 指令 + 可选的阶段专属 prompt</b>。
     *
     * <p>这是整个 agent 系统最重要的 prompt 组装方法，所有 System Prompt 最终都经过这里。
     * 面试时如果能讲清楚这个三段式结构及其设计意图，基本就掌握了 agent 的 prompt 架构。</p>
     *
     * <h3>三段式结构</h3>
     * <ol>
     *   <li><b>时间基准（动态）</b>：由 {@code LocalDate.now()} 计算，包含
     *       <ul>
     *         <li>当前精确时间："2026年05月25日（星期一，2026年5月25日）"</li>
     *         <li>相对年份映射："用户说2026年，指2026年；说去年，指2025年；说今年，指2026年..."</li>
     *         <li>输出约束："所有输出内容须标明具体公历年份，禁止使用仅含「去年」「明年」等表述"</li>
     *       </ul>
     *       <b>面试要点</b>：为什么每天动态计算而不是写死？因为 agent 长期运行，
     *       去年/今年的含义随时间变化。写死会导致跨年后"去年"指代错误。</li>
     *   <li><b>全局 agent 指令（静态）</b>：来自 {@code agentRunSystemPrompt}
     *       （通常是 {@code agent_run_system.txt}），包含角色定义、通用约束等。
     *       这部分内容跨阶段保持不变，利于 OpenAI 兼容 API 的自动 KV 前缀缓存。</li>
     *   <li><b>阶段专属 prompt（配置）</b>：{@code specificPrompt} 参数，
     *       仅在内容与 global 不同时才拼接，避免重复。</li>
     * </ol>
     *
     * <h3>与 {@link #dynamicContextPrefix()} 的关系</h3>
     * <p>{@code dynamicContextPrefix()} 返回 "今天是2026年05月25日。" 并注入到 User Message。
     * 这与本方法在 System Prompt 中注入的时间基准形成<b>双重时间锚点</b>：
     * System Prompt 一次 + User Message 一次，增强 LLM 的时间感知。</p>
     *
     * @param specificPrompt 阶段专属 prompt 文本（如 todo planner 专用指令），可为空
     * @return 三段拼接后的完整 System Prompt
     */
    private String composeSystemPrompt(String specificPrompt) {
        String global = firstNonBlank(currentPrompts().getAgentRunSystemPrompt(), "");
        String specific = firstNonBlank(specificPrompt, "");

        List<String> parts = new ArrayList<>();
        // 第一段：时间基准 —— 动态计算，确保 LLM 正确推理"去年/今年/明年"等相对时间
        LocalDate today = LocalDate.now();
        int thisYear = today.getYear();
        int lastYear = thisYear - 1;
        int yearBeforeLast = thisYear - 2;
        parts.add(String.format(
                "当前时间：%s（%s，%d年%d月%d日）。所有涉及日期、时间、年份的推理必须以当前时间为基准。"
                + "例如：用户说%d年，指%d年；说去年，指%d年；说今年，指%d年；"
                + "说上一年，指%d年；说再上一年，指%d年。"
                + "所有输出内容须标明具体公历年份，禁止使用仅含「去年」「明年」「上一年」「再上一年」等未标明具体年份的表述。",
                today.format(CN_DATE_FORMATTER),
                today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE),
                thisYear, today.getMonthValue(), today.getDayOfMonth(),
                thisYear, thisYear,
                lastYear, thisYear,
                lastYear, yearBeforeLast));
        // 第二段：全局 agent 角色定义（跨阶段共享，利于 KV 缓存）
        if (!global.isBlank()) {
            parts.add(global);
        }
        // 第三段：阶段专属 prompt——只在与 global 内容不同时才追加，避免 System Prompt 冗余
        if (!specific.isBlank() && !specific.equals(global)) {
            parts.add(specific);
        }
        return String.join("\n", parts).trim();
    }

    /**
     * 合并 Nacos 热加载配置与 Spring 静态配置，得到当前生效的 Prompts（提示词集合）。
     *
     * <p><b>合并策略</b>：按字段粒度合并——本地热加载（Nacos）每个字段非空则覆盖静态（application.yml）的同名字段。
     * 不是整体替换，所以可以只热更新部分 prompt 字段（如只改 dagReactSystemPrompt），
     * 其他字段仍沿用静态配置值。</p>
     *
     * <p><b>为什么需要字段级合并</b>：如果整体替换，热加载配置必须包含全部 prompt 字段，
     * 否则未配置的字段会变成 null。字段级合并允许 Nacos 只下发改动的字段，
     * 大大降低配置维护成本和出错概率。</p>
     *
     * @return 合并后的 Prompts 对象（永不为 null）
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
        // 逐字段合并：Nacos 热加载值优先，为空时 fallback 到 application.yml 静态值
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
     * 合并热加载与静态的 SubAgent（子代理）配置。
     *
     * <p>合并策略与 {@link #currentPrompts()} 相同：字段级合并，本地非空则覆盖静态。
     * 支持的字段包括：enabled（是否启用）、complexityThreshold（复杂度阈值）、
     * maxSteps（最大步骤数）、maxCount（最大并行数）、endpointName（LLM 端点）、
     * modelName 及各复杂度等级的模型名。</p>
     *
     * @return 合并后的 SubAgent 配置，静态配置全部为空时返回 null
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
     * 基于启发式特征估算 Sub-Agent 任务的复杂度，用于自动选择合适的模型档位。
     *
     * <p><b>评分规则</b>（每命中一项 +1 分）：</p>
     * <ul>
     *   <li>文本长度 ＞ 180：score +1（描述越长通常越复杂）</li>
     *   <li>文本长度 ＞ 360：score 再 +1（超长文本累计 +2）</li>
     *   <li>包含"并行 / parallel / 多个 / multi"等并行关键词：score +1</li>
     *   <li>包含"组合 / 回测 / 夏普 / 最大回撤"等量化分析关键词：score +1</li>
     *   <li>包含"并且 / 同时 / 另外 / 此外"等多任务连接词：score +1</li>
     * </ul>
     *
     * <p>注意：goal 和 context 合并后计算长度和关键词匹配，
     * 因此短 goal + 长 context 的场景也可能被判为高复杂度。</p>
     *
     * <p><b>阈值</b>：</p>
     * <ul>
     *   <li>score ≥ 4 → {@link Complexity#HIGH}（高强度，如多资产回测 + 市场分析）→ 选最强模型（如 GPT-5.4）</li>
     *   <li>score ≥ 2 → {@link Complexity#MEDIUM}（中等，如单资产数据查询 + 简单计算）→ 选中档模型（如 Kimi K2.5）</li>
     *   <li>score ＜ 2 → {@link Complexity#LOW}（简单，如搜索一个代码）→ 选便宜模型（如 Qwen 122B）</li>
     * </ul>
     *
     * <p><b>面试要点</b>：这是一个简单但有效的启发式方法。不需要真正"理解"任务语义，
     * 只需要看文本长度和关键词就能做出合理的复杂度判断。比硬编码所有 Sub-Agent 用同一个模型更经济。</p>
     *
     * @param goal    Sub-Agent 的任务目标文本
     * @param context 补充上下文文本
     * @return 复杂度枚举值
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

    /**
     * 返回 first 非空时取 first，否则取 second。
     * 用于配置字段级合并时热加载值覆盖静态值。
     */
    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    /** Sub-Agent 任务复杂度三档，驱动模型自动选择。 */
    private enum Complexity {
        /** 简单任务——如搜索单个代码、获取单只股票信息，用最便宜的模型即可 */
        LOW,
        /** 中等任务——如批量数据查询 + 简单计算，用中档模型 */
        MEDIUM,
        /** 高强度任务——如多资产回测 + 市场分析 + Python 计算，需要最强模型 */
        HIGH
    }

    /**
     * 简易模板渲染：把 {@code {{key}}} 占位符替换为 vars 中的实际值。
     *
     * <p>例如模板 "可用工具: {{toolWhitelist}}" + vars {toolWhitelist: "searchIndex,getIndexDaily"}
     * → "可用工具: searchIndex,getIndexDaily"。</p>
     *
     * <p>不支持循环、条件等高级模板语法——prompt 模板只需简单的占位符替换。</p>
     *
     * @param template 含 {{key}} 占位符的模板字符串，为 null 时返回空串
     * @param vars     占位符到实际值的映射
     * @return 替换后的文本
     */
    private String render(String template, Map<String, String> vars) {
        String out = safe(template);
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            out = out.replace("{{" + entry.getKey() + "}}", safe(entry.getValue()));
        }
        return out;
    }

    /**
     * 列表选择合并：本地列表非空则用本地，否则用静态配置。
     *
     * <p>列表类配置不做字段级合并（因为列表的语义是整体替换而非逐元素覆盖）。
     * 例如 pythonRefineRequirements 本地配置了 3 条规则，就完整使用这 3 条，
     * 不会尝试和静态配置的规则逐条对比合并。</p>
     */
    private <T> List<T> selectList(List<T> local, List<T> base) {
        if (local != null && !local.isEmpty()) {
            return local;
        }
        return base == null ? List.of() : base;
    }

    /**
     * 取第一个非空白（非 null 且 trim 后非空）的字符串值。
     * 全部为空时返回空串。可用于配置字段多级 fallback 等场景。
     */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    /** 空安全工具方法：null 转空串，避免 NullPointerException。 */
    private String safe(String text) {
        return text == null ? "" : text;
    }
}

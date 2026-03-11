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

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentPromptService {

    private static final DateTimeFormatter CN_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日");
    private final AgentLlmProperties properties;
    private final AgentLlmLocalConfigLoader localConfigLoader;

    public String agentRunSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getAgentRunSystemPrompt(), ""));
    }

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

    public String workflowFinalSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(
                currentPrompts().getWorkflowFinalSystemPrompt(),
                currentPrompts().getParallelFinalSystemPrompt(),
                ""
        ));
    }

    public String workflowTodoRecoverySystemPrompt() {
        return composeSystemPrompt(firstNonBlank(
                currentPrompts().getWorkflowTodoRecoverySystemPrompt(),
                ""
        ));
    }

    public String parallelPlannerSystemPrompt(String toolWhitelist,
                                              int maxTasks,
                                              int maxSubSteps,
                                              int maxParallelTasks,
                                              int maxSubAgents) {
        return parallelPlannerSystemPrompt(toolWhitelist, maxTasks, maxSubSteps, maxParallelTasks, maxSubAgents, 1, 1);
    }

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
                "candidateIndex", String.valueOf(Math.max(candidateIndex, 1)),
                "candidateCount", String.valueOf(Math.max(candidateCount, 1))
        ));
        return composeSystemPrompt(specific);
    }

    public String parallelFinalSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getParallelFinalSystemPrompt(), ""));
    }

    public String parallelPatchPlannerSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getParallelPatchPlannerSystemPromptTemplate(), ""));
    }

    public String planJudgeSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getPlanJudgeSystemPromptTemplate(), ""));
    }

    public String planJudgeRuntimeSystemPrompt() {
        // 优先使用运行时 Plan Judge prompt，如果没有配置则使用默认的
        String runtimePrompt = firstNonBlank(currentPrompts().getPlanJudgeRuntimeSystemPromptTemplate(), "");
        if (!runtimePrompt.isEmpty()) {
            return composeSystemPrompt(runtimePrompt);
        }
        // 向后兼容：如果没有配置运行时 prompt，使用原来的 prompt
        return planJudgeSystemPrompt();
    }

    public String semanticJudgeSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getSemanticJudgeSystemPromptTemplate(), ""));
    }

    public String subAgentPlannerSystemPrompt(String tools, int maxSteps) {
        String template = firstNonBlank(currentPrompts().getSubAgentPlannerSystemPromptTemplate(),
                "");
        String specific = render(template, Map.of(
                "tools", safe(tools),
                "maxSteps", String.valueOf(maxSteps)
        ));
        return composeSystemPrompt(specific);
    }

    public String subAgentSummarySystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getSubAgentSummarySystemPrompt(), ""));
    }

    public String pythonRefineSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getPythonRefineSystemPrompt(), ""));
    }

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

    public String pythonRefineOutputInstruction() {
        return firstNonBlank(currentPrompts().getPythonRefineOutputInstruction());
    }

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

    public String orchestratorPlanningSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getOrchestratorPlanningSystemPrompt(), ""));
    }

    public String orchestratorSummarySystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getOrchestratorSummarySystemPrompt(), ""));
    }

    /**
     * DAG ReAct 执行阶段的 System Prompt。
     * 用于 ReactTodoExecutor 构建 DAG 节点执行时的 System Message。
     *
     * 加载优先级：
     * 1. agent.llm.prompts.dagReactSystemPrompt（直接配置内容）
     * 2. agent.llm.prompts.dagReactSystemPromptFile（配置文件路径）
     * 3. 代码中的默认 Prompt
     */
    public String dagReactSystemPrompt() {
        // 1. 优先使用直接配置的内容
        String directPrompt = currentPrompts().getDagReactSystemPrompt();
        if (!directPrompt.isBlank()) {
            return directPrompt;
        }

        // 2. 尝试从配置的文件路径加载
        String filePath = currentPrompts().getDagReactSystemPromptFile();
        if (!filePath.isBlank()) {
            String fileContent = loadPromptFromConfiguredPath(filePath);
            if (!fileContent.isBlank()) {
                return fileContent;
            }
        }

        // 3. 使用默认 Prompt
        return defaultDagReactSystemPrompt();
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
     * 从配置的文件路径加载 Prompt 内容。
     * 支持绝对路径或相对于工作目录的路径。
     */
    private String loadPromptFromConfiguredPath(String filePath) {
        try {
            java.nio.file.Path path = java.nio.file.Path.of(filePath);
            if (java.nio.file.Files.exists(path)) {
                return java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to load prompt from configured path: {}, error: {}", filePath, e.getMessage());
        }
        return "";
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
            case HIGH -> firstNonBlank(high, medium, low, fallback);
            case MEDIUM -> firstNonBlank(medium, high, low, fallback);
            case LOW -> firstNonBlank(low, medium, high, fallback);
        };
    }

    /**
     * 默认的 DAG ReAct System Prompt（当配置文件不存在时使用）。
     * Sub-Agent 最大并行数从配置读取（agent.llm.runtime.subAgent.maxCount），不硬编码。
     */
    private String defaultDagReactSystemPrompt() {
        int maxSubAgents = maxSubAgentCount();
        return "你是金融数据分析助手，负责执行分析任务节点。\n\n"
            + "## 可用工具及参数规范（必须严格使用指定的参数名）\n"
            + "- searchIndex: {\"keyword\": \"<搜索关键词>\"}\n"
            + "- searchStock: {\"keyword\": \"<搜索关键词>\"}\n"
            + "- searchFund: {\"keyword\": \"<搜索关键词>\"}\n"
            + "- getIndexDaily: {\"ts_code\": \"<指数代码>\", \"start_date\": \"YYYYMMDD\", \"end_date\": \"YYYYMMDD\"}\n"
            + "- getStockDaily: {\"ts_code\": \"<股票代码>\", \"start_date\": \"YYYYMMDD\", \"end_date\": \"YYYYMMDD\"}\n"
            + "- getFundDaily: {\"ts_code\": \"<基金代码>\", \"start_date\": \"YYYYMMDD\", \"end_date\": \"YYYYMMDD\"}\n"
            + "- getIndexInfo: {\"ts_code\": \"<指数代码>\"}\n"
            + "- getStockInfo: {\"ts_code\": \"<股票代码>\"}\n"
            + "- executePython: {\"code\": \"<Python代码>\", \"dataset_ids\": \"<必需：数据集ID列表，逗号分隔>\"}\n\n"
            + "## Sub-Agent 工具（用于并行化复杂子任务，最多启动 " + maxSubAgents + " 个子代理）\n"
            + "- spawnSubAgent: {\"goal\": \"<子代理目标描述>\", \"context\": \"<可选补充上下文>\"}\n"
            + "  → 在后台线程启动子代理执行目标，立即返回 sub_agent_id。主线程可继续处理其他工作。\n"
            + "- waitForSubAgent: {\"sub_agent_ids\": \"<ID列表，逗号分隔，如 sa_0,sa_1；单个ID也可直接传>\"}\n"
            + "  → 可选地同时等待一个或多个子代理完成，由你的判断决定何时汇总。返回每个子代理的执行结果。\n\n"
            + "## Sub-Agent 使用模式\n"
            + "当某个子任务复杂且可以并行化时，例如需要分别处理多个独立数据源：\n"
            + "  思路：\"这个子任务很复杂，让我启动一个 sub-agent 来并行处理。\"\n"
            + "  → 调用 spawnSubAgent(goal=<子任务描述>)，不阻塞主线程\n"
            + "  → 主线程继续处理其他可以同步完成的部分\n"
            + "  → 根据需要，可选地调用 waitForSubAgent 同时等待一个或多个已启动的子代理\n"
            + "  → 汇总所有结果，输出最终回答\n\n"
            + "## executePython 使用指南\n"
            + "1. dataset_ids 是必需参数，格式为逗号分隔的数据集ID\n"
            + "2. 代码中通过遍历 /sandbox/input/*/ 可以访问所有挂载的数据集\n"
            + "3. 每个数据集目录中包含 data.csv 和 data.meta.json 文件\n"
            + "4. 读取数据示例：\n"
            + "   ```python\n"
            + "   import pandas as pd\n"
            + "   import os\n"
            + "   \n"
            + "   # 读取所有数据集\n"
            + "   for root, dirs, files in os.walk('/sandbox/input/'):\n"
            + "       if 'data.csv' in files:\n"
            + "           df = pd.read_csv(os.path.join(root, 'data.csv'))\n"
            + "           # 进行数据分析...\n"
            + "   ```\n"
            + "5. 工具返回的 stdout 中会显示找到的文件路径，你应当直接读取这些文件\n"
            + "6. 不要反复列出目录，看到文件路径后直接读取 CSV 进行分析\n\n"
            + "## 重要警告\n"
            + "1. 必须严格使用上述指定的参数名，否则工具调用会失败\n"
            + "2. executePython 的 dataset_ids 是必需参数，必须来自依赖任务的 data.dataset_id\n"
            + "3. 看到工具返回的文件路径后，直接读取 CSV 进行计算，不要重复列出目录\n\n"
            + "## 输出格式\n"
            + "调用工具: {\"tool\": \"<工具名>\", \"params\": {\"<参数名>\": \"<参数值>\"}}\n"
            + "直接回答: {\"answer\": \"<你的回答>\"}\n";
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
     */
    public String planningAnalysisStageInstruction(String toolWhitelist, int maxTodos) {
        String template = firstNonBlank(
                currentPrompts().getTodoPlannerSystemPromptTemplate(),
                """
                你是任务规划专家。请把用户目标拆解为 Todo List。
                规则:
                1) 只能使用工具: {{toolWhitelist}}
                2) 总步骤数不超过 {{maxTodos}}
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
        if (!specific.isBlank() && !specific.equals(global)) {
            parts.add(specific);
        }
        return String.join("\n", parts).trim();
    }

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
        return merged;
    }

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

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private enum Complexity {
        LOW,
        MEDIUM,
        HIGH
    }

    private String render(String template, Map<String, String> vars) {
        String out = safe(template);
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            out = out.replace("{{" + entry.getKey() + "}}", safe(entry.getValue()));
        }
        return out;
    }

    private <T> List<T> selectList(List<T> local, List<T> base) {
        if (local != null && !local.isEmpty()) {
            return local;
        }
        return base == null ? List.of() : base;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }
}

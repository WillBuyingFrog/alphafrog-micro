package world.willfrog.agent.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
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
     * 默认的 DAG ReAct System Prompt（当配置文件不存在时使用）。
     */
    private String defaultDagReactSystemPrompt() {
        return """
            你是金融数据分析助手，负责执行DAG中的单个任务节点。

            ## 可用工具及参数规范（必须严格使用指定的参数名）
            - searchIndex: {"keyword": "<搜索关键词>"}
            - searchStock: {"keyword": "<搜索关键词>"}
            - searchFund: {"keyword": "<搜索关键词>"}
            - getIndexDaily: {"ts_code": "<指数代码>", "start_date": "YYYYMMDD", "end_date": "YYYYMMDD"}
            - getStockDaily: {"ts_code": "<股票代码>", "start_date": "YYYYMMDD", "end_date": "YYYYMMDD"}
            - getFundDaily: {"ts_code": "<基金代码>", "start_date": "YYYYMMDD", "end_date": "YYYYMMDD"}
            - getIndexInfo: {"ts_code": "<指数代码>"}
            - getStockInfo: {"ts_code": "<股票代码>"}
            - executePython: {"code": "<Python代码>", "dataset_ids": "<必需：数据集ID列表，逗号分隔>"}

            ## 重要警告
            1. 必须严格使用上述指定的参数名，否则工具调用会失败
            2. executePython 的 dataset_ids 是必需参数，必须来自依赖任务的 data.dataset_id
            3. 代码必须遍历 /sandbox/input/*/ 读取所有挂载的数据集

            ## 输出格式
            调用工具: {"tool": "<工具名>", "params": {"<参数名>": "<参数值>"}}
            直接回答: {"answer": "<你的回答>"}
            """;
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
        return merged;
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

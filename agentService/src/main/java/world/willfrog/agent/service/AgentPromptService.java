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

    private String composeSystemPrompt(String specificPrompt) {
        String global = firstNonBlank(currentPrompts().getAgentRunSystemPrompt(), "");
        String specific = firstNonBlank(specificPrompt, "");
        String todayLine = "今天是" + LocalDate.now().format(CN_DATE_FORMATTER) + "。";

        List<String> parts = new ArrayList<>();
        parts.add(todayLine);
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
        merged.setSemanticJudgeSystemPromptTemplate(firstNonBlank(local.getSemanticJudgeSystemPromptTemplate(), base.getSemanticJudgeSystemPromptTemplate()));
        merged.setSubAgentPlannerSystemPromptTemplate(firstNonBlank(local.getSubAgentPlannerSystemPromptTemplate(), base.getSubAgentPlannerSystemPromptTemplate()));
        merged.setSubAgentSummarySystemPrompt(firstNonBlank(local.getSubAgentSummarySystemPrompt(), base.getSubAgentSummarySystemPrompt()));
        merged.setPythonRefineSystemPrompt(firstNonBlank(local.getPythonRefineSystemPrompt(), base.getPythonRefineSystemPrompt()));
        merged.setPythonRefineOutputInstruction(firstNonBlank(local.getPythonRefineOutputInstruction(), base.getPythonRefineOutputInstruction()));
        merged.setOrchestratorPlanningSystemPrompt(firstNonBlank(local.getOrchestratorPlanningSystemPrompt(), base.getOrchestratorPlanningSystemPrompt()));
        merged.setOrchestratorSummarySystemPrompt(firstNonBlank(local.getOrchestratorSummarySystemPrompt(), base.getOrchestratorSummarySystemPrompt()));
        merged.setPythonRefineRequirements(selectList(local.getPythonRefineRequirements(), base.getPythonRefineRequirements()));
        merged.setDatasetFieldSpecs(selectList(local.getDatasetFieldSpecs(), base.getDatasetFieldSpecs()));
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

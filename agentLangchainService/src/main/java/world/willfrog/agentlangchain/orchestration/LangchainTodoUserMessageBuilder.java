package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.agent.tool.ToolSpecification;
import world.willfrog.agent.platform.service.AgentPromptService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Builds todo / final user messages aligned with legacy {@code ReactTodoExecutor} / {@code LinearWorkflowExecutor}.
 */
final class LangchainTodoUserMessageBuilder {

    private LangchainTodoUserMessageBuilder() {
    }

    static String buildTodoUserMessage(AgentPromptService promptService,
                                       String userGoal,
                                       List<LangchainCompletedTodo> completedTodos,
                                       Map<String, String> datasetRefs,
                                       String todoDescription,
                                       List<ToolSpecification> toolSpecifications) {
        StringBuilder message = new StringBuilder();
        message.append(promptService.dynamicContextPrefix()).append("\n\n");
        message.append("用户目标：").append(safe(userGoal)).append("\n\n");
        Set<String> toolNames = resolveToolNames(toolSpecifications);
        if (!toolNames.isEmpty()) {
            message.append("当前可用工具：").append(String.join(", ", toolNames)).append("\n\n");
        }
        if (completedTodos != null) {
            for (LangchainCompletedTodo todo : completedTodos) {
                message.append("已完成: ").append(safe(todo.getDescription())).append("\n");
                message.append("摘要: ").append(safe(todo.getSummary())).append("\n");
                message.append("输出: ").append(safe(todo.getOutput())).append("\n\n");
            }
        }
        message.append("当前任务: ").append(safe(todoDescription)).append("\n\n");
        if (datasetRefs != null && !datasetRefs.isEmpty()) {
            message.append("已有数据集 (可用于 dataset_ids 参数):\n");
            datasetRefs.keySet().forEach(id -> message.append("  - ").append(id).append("\n"));
            message.append("\n");
            message.append("⚠️ 注意：如果调用 executePython，必须将上述 dataset ID 通过 dataset_ids 参数传入！\n\n");
        }
        message.append("请决定如何完成。\n");
        message.append("需要调用工具时请直接使用系统提供的工具调用能力，不要手写 JSON。\n");
        message.append("如果当前任务要求调用工具，下一条消息必须是实际工具调用；不要只说明计划或展示参数。\n");
        message.append("无需工具时，请直接输出最终回答内容。\n");
        message.append("⚠️ 警告：工具参数名必须与工具规范完全一致。");
        return message.toString();
    }

    static String buildFinalUserMessage(AgentPromptService promptService,
                                        String userGoal,
                                        List<LangchainCompletedTodo> completedTodos) {
        StringBuilder context = new StringBuilder();
        context.append(promptService.finalAnswerStageInstruction()).append("\n\n");
        context.append(promptService.dynamicContextPrefix()).append("\n\n");
        context.append("用户问题：").append(safe(userGoal)).append("\n\n");
        context.append("已完成的任务：\n");
        if (completedTodos != null) {
            for (LangchainCompletedTodo todo : completedTodos) {
                context.append("- ").append(safe(todo.getDescription())).append(": ")
                        .append(safe(todo.getSummary())).append("\n");
                if (todo.getOutput() != null && !todo.getOutput().isBlank()) {
                    context.append("  输出: ").append(todo.getOutput()).append("\n");
                }
            }
        }
        context.append("\n请根据以上所有任务结果，生成对用户问题可直接展示的最终回答。");
        context.append("\n请直接输出 Markdown，不要把回答包在 JSON 或代码块里。");
        return context.toString();
    }

    static Map<String, String> newDatasetRefMap() {
        return new LinkedHashMap<>();
    }

    private static Set<String> resolveToolNames(List<ToolSpecification> specifications) {
        if (specifications == null || specifications.isEmpty()) {
            return Set.of();
        }
        return specifications.stream()
                .map(ToolSpecification::name)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

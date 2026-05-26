package world.willfrog.agent.workflow;

import java.util.List;

/**
 * Final answer 生成辅助：success 路径禁止 silent fallback。
 */
final class FinalAnswerSupport {

    private FinalAnswerSupport() {
    }

    static String resolveAnswerOrEmpty(String llmText) {
        return llmText != null && !llmText.isBlank() ? llmText : "";
    }

    static String lastNonBlankTodoOutput(List<CompletedTodoInfo> completedTodos) {
        if (completedTodos == null || completedTodos.isEmpty()) {
            return "";
        }
        for (int i = completedTodos.size() - 1; i >= 0; i--) {
            CompletedTodoInfo todo = completedTodos.get(i);
            if (todo != null && todo.getOutput() != null && !todo.getOutput().isBlank()) {
                return todo.getOutput();
            }
        }
        return "";
    }
}

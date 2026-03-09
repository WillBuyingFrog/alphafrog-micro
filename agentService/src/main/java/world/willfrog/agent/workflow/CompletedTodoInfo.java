package world.willfrog.agent.workflow;

import lombok.Builder;
import lombok.Data;

/**
 * 已完成的 Todo 信息，用于在 ReAct 上下文中传递。
 */
@Data
@Builder
public class CompletedTodoInfo {
    private String todoId;
    private String description;
    private String output;
    private String summary;
}

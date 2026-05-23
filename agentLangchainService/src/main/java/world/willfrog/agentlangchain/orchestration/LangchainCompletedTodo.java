package world.willfrog.agentlangchain.orchestration;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LangchainCompletedTodo {
    private String todoId;
    private int sequence;
    private String description;
    private String output;
    private String summary;
}

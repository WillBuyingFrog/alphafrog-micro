package world.willfrog.agentlangchain.orchestration;

import lombok.Builder;
import lombok.Data;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class LangchainLinearWorkflowResult {
    private boolean success;
    /** True when canceled/paused mid-flight; pipeline must not overwrite control terminal state. */
    private boolean interrupted;
    private String failureReason;
    private String finalAnswer;
    private LangchainTodoPlan plan;
    @Builder.Default
    private List<LangchainCompletedTodo> completedTodos = new ArrayList<>();
    private int toolCallsUsed;
}

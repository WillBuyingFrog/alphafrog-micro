package world.willfrog.agentlangchain.orchestration;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LangchainTodoNodeResult {
    private boolean success;
    private String output;
    private String summary;
    private String failureReason;
    @Builder.Default
    private int toolCallsUsed = 0;

    public static LangchainTodoNodeResult success(String output, int toolCallsUsed) {
        String trimmed = output == null ? "" : output.trim();
        return LangchainTodoNodeResult.builder()
                .success(true)
                .output(trimmed)
                .summary(trimmed)
                .toolCallsUsed(toolCallsUsed)
                .build();
    }

    public static LangchainTodoNodeResult failure(String reason) {
        return LangchainTodoNodeResult.builder()
                .success(false)
                .failureReason(reason)
                .summary(reason)
                .output("")
                .build();
    }

    public static LangchainTodoNodeResult skipped(String dependencyId) {
        String reason = "Skipped: dependency " + dependencyId + " failed";
        return LangchainTodoNodeResult.builder()
                .success(false)
                .failureReason(reason)
                .summary(reason)
                .output("")
                .build();
    }
}

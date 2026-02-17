package world.willfrog.alphafrogmicro.frontend.model.agent;

public record AgentConfigResponse(
        RetentionDays retentionDays,
        int maxPollingInterval,
        Features features
) {
    public record RetentionDays(
            int normal,
            int admin
    ) {
    }

    public record Features(
            boolean parallelExecution,
            boolean pauseResume
    ) {
    }
}

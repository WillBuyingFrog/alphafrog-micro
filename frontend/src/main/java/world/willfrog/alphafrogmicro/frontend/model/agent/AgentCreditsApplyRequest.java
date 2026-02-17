package world.willfrog.alphafrogmicro.frontend.model.agent;

public record AgentCreditsApplyRequest(
        Integer amount,
        String reason,
        String contact
) {
}

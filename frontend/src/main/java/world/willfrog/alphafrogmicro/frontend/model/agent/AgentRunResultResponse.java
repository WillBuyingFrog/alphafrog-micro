package world.willfrog.alphafrogmicro.frontend.model.agent;

public record AgentRunResultResponse(
        String id,
        String status,
        String answer,
        String answerMarkdown,
        Object structuredAnswer,
        Object payload,
        Object observability,
        Integer totalCreditsConsumed
) {
}

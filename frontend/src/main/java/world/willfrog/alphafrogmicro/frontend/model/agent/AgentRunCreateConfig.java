package world.willfrog.alphafrogmicro.frontend.model.agent;

import java.util.List;

public record AgentRunCreateConfig(
        String model,
        WebSearch webSearch,
        CodeInterpreter codeInterpreter,
        SmartRetrieval smartRetrieval
) {
    public record WebSearch(
            Boolean enabled,
            List<String> sources
    ) {
    }

    public record CodeInterpreter(
            Boolean enabled,
            Integer maxCredits
    ) {
    }

    public record SmartRetrieval(
            Boolean enabled,
            List<String> sources
    ) {
    }
}

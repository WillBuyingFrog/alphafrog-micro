package world.willfrog.alphafrogmicro.frontend.model.agent;

import java.util.List;
import java.util.Map;

public record TraceListResponse(
        List<TraceSpanItem> spans,
        TraceSummary summary
) {
    public record TraceSummary(
            long totalLlmCalls,
            long totalToolCalls,
            long totalDurationMs,
            long totalTokens
    ) {
    }
}

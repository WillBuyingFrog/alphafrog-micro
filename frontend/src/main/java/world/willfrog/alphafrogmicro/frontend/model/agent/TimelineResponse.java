package world.willfrog.alphafrogmicro.frontend.model.agent;

import java.util.List;

public record TimelineResponse(
        List<TimelineItem> items,
        int nextAfterSeq,
        boolean hasMore
) {
    public record TimelineItem(
            int seq,
            String source,
            String traceId,
            String type,
            String time,
            String title,
            Long durationMs,
            Object detail
    ) {
    }
}

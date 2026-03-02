package world.willfrog.alphafrogmicro.frontend.model.market;

import java.util.List;

public record MarketNewsResponse(
        List<MarketNewsItemResponse> data,
        String updatedAt,
        String provider
) {
}

package world.willfrog.alphafrogmicro.frontend.model.market;

public record MarketNewsItemResponse(
        String id,
        String timestamp,
        String title,
        String source,
        String category,
        String url
) {
}

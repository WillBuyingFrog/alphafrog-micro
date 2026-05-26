package world.willfrog.externalinfo.search.backend;

/**
 * 单个搜索结果（原始层）
 */
public record BackendHit(
        String title,
        String url,
        String snippet,
        String source,
        String publishedDate,
        Float score
) {}

package world.willfrog.externalinfo.search.backend;

/**
 * 引用来源
 */
public record BackendCitation(
        int index,
        String url,
        String title
) {}

package world.willfrog.externalinfo.search.backend;

/**
 * Backend 元信息
 */
public record BackendMeta(
        String backend,
        String modelOrStrength,
        Integer costEstimateMs,
        String rawQuerySent
) {}

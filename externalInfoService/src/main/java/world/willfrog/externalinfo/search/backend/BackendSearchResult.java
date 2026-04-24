package world.willfrog.externalinfo.search.backend;

import java.util.List;

/**
 * 各 backend 原始响应的统一结构。
 * 由具体 backend 实现填充，再由 ResultNormalizer 映射为 WebSearchResponse。
 */
public record BackendSearchResult(
        List<BackendHit> hits,
        String answer,
        List<BackendCitation> citations,
        BackendMeta meta,
        boolean ok,
        String errorCode,
        String errorMessage
) {

    public static BackendSearchResult error(String backendName, String errorCode, String errorMessage) {
        return new BackendSearchResult(
                List.of(), null, List.of(),
                new BackendMeta(backendName, null, null, null),
                false, errorCode, errorMessage
        );
    }
}

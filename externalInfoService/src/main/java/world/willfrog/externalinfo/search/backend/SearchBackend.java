package world.willfrog.externalinfo.search.backend;

import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRequest;

/**
 * 统一搜索后端接口。
 * 所有联网搜索后端（Perplexity、Tavily、Exa）必须实现此接口。
 */
public interface SearchBackend {

    /**
     * 后端名称，如 "perplexity"、"tavily"、"exa"
     */
    String name();

    /**
     * 执行搜索
     */
    BackendSearchResult search(WebSearchRequest request);

    /**
     * 是否支持指定场景
     */
    boolean supportsScene(String scene);

    /**
     * 是否支持指定强度档位
     */
    boolean supportsStrength(String strength);
}

package world.willfrog.externalinfo.search;

import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRequest;
import world.willfrog.externalinfo.config.SearchLlmProperties;

import java.util.List;

/**
 * WebSearch 内部执行上下文。
 * 不扩展对外 proto，preset/domain/timeRange 等后端参数在服务内解析传递。
 */
public record WebSearchExecutionContext(
        WebSearchRequest request,
        String backend,
        String scene,
        String strength,
        int maxResults,
        String timeRange,
        List<String> includeDomains,
        List<String> excludeDomains,
        SearchLlmProperties.WebSearchPreset preset,
        SearchLlmConfigResolver.ResolvedBackendConfig backendConfig
) {
}

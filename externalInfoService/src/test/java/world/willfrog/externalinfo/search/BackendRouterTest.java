package world.willfrog.externalinfo.search;

import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRequest;
import world.willfrog.externalinfo.config.SearchLlmProperties;
import world.willfrog.externalinfo.search.backend.BackendSearchResult;
import world.willfrog.externalinfo.search.backend.SearchBackend;
import world.willfrog.externalinfo.service.SearchLlmLocalConfigLoader;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackendRouterTest {

    @Test
    void resolve_shouldUseDefaultPresetWhenDefaultPresetMatchesScene() {
        SearchLlmProperties props = properties();
        props.getFeatures().getWebSearch().setDefaultPreset("finance_default");
        props.getFeatures().getWebSearch().setPresets(Map.of(
                "finance_deep", preset("finance", "tavily", "deep", 10),
                "finance_default", preset("finance", "perplexity", "standard", 8)
        ));

        BackendRouter router = router(props);
        BackendRouter.ResolvedBackend resolved = router.resolve(WebSearchRequest.newBuilder()
                .setScene("finance")
                .build());

        assertEquals("perplexity", resolved.backend().name());
        assertEquals("standard", resolved.context().strength());
        assertEquals(8, resolved.context().maxResults());
    }

    @Test
    void resolve_shouldUseSceneMatchedPresetBeforeDefaultPreset() {
        SearchLlmProperties props = properties();
        props.getFeatures().getWebSearch().setDefaultPreset("finance_default");
        props.getFeatures().getWebSearch().setPresets(Map.of(
                "news_fast", preset("news", "exa", "fast", 6),
                "finance_default", preset("finance", "perplexity", "standard", 8)
        ));

        BackendRouter router = router(props);
        BackendRouter.ResolvedBackend resolved = router.resolve(WebSearchRequest.newBuilder()
                .setScene("news")
                .build());

        assertEquals("exa", resolved.backend().name());
        assertEquals("news", resolved.context().scene());
        assertEquals("fast", resolved.context().strength());
        assertEquals(6, resolved.context().maxResults());
    }

    @Test
    void resolve_shouldAllowBackendFieldAsPresetName() {
        SearchLlmProperties props = properties();
        props.getFeatures().getWebSearch().setDefaultPreset("finance_default");
        props.getFeatures().getWebSearch().setPresets(Map.of(
                "news_fast", preset("news", "exa", "fast", 6),
                "finance_default", preset("finance", "perplexity", "standard", 8)
        ));

        BackendRouter router = router(props);
        BackendRouter.ResolvedBackend resolved = router.resolve(WebSearchRequest.newBuilder()
                .setBackend("news_fast")
                .build());

        assertEquals("exa", resolved.backend().name());
        assertEquals("news", resolved.context().scene());
        assertEquals("fast", resolved.context().strength());
        assertEquals(6, resolved.context().maxResults());
    }

    private BackendRouter router(SearchLlmProperties props) {
        SearchLlmLocalConfigLoader loader = mock(SearchLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.empty());
        SearchLlmConfigResolver resolver = new SearchLlmConfigResolver(props, loader);
        return new BackendRouter(resolver, List.of(backend("perplexity"), backend("tavily"), backend("exa")));
    }

    private SearchLlmProperties properties() {
        SearchLlmProperties props = new SearchLlmProperties();
        props.setProviders(Map.of(
                "perplexity", provider(),
                "tavily", provider(),
                "exa", provider()
        ));
        return props;
    }

    private SearchLlmProperties.Provider provider() {
        SearchLlmProperties.Provider provider = new SearchLlmProperties.Provider();
        provider.setBaseUrl("https://example.test");
        provider.setApiKey("key");
        return provider;
    }

    private SearchLlmProperties.WebSearchPreset preset(String scene, String backend, String strength, int maxResults) {
        SearchLlmProperties.WebSearchPreset preset = new SearchLlmProperties.WebSearchPreset();
        preset.setScene(scene);
        preset.setBackend(backend);
        preset.setStrength(strength);
        preset.setMaxResults(maxResults);
        return preset;
    }

    private SearchBackend backend(String name) {
        return new SearchBackend() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public BackendSearchResult search(WebSearchExecutionContext context) {
                return null;
            }

            @Override
            public boolean supportsScene(String scene) {
                return true;
            }

            @Override
            public boolean supportsStrength(String strength) {
                return true;
            }
        };
    }
}

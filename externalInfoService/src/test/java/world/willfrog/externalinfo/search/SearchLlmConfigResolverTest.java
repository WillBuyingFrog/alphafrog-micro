package world.willfrog.externalinfo.search;

import org.junit.jupiter.api.Test;
import world.willfrog.externalinfo.config.SearchLlmProperties;
import world.willfrog.externalinfo.service.SearchLlmLocalConfigLoader;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchLlmConfigResolverTest {

    @Test
    void resolveBackendConfig_shouldFallbackAuthFromProviderWhenWebSearchBackendOmitsKey() {
        SearchLlmProperties props = new SearchLlmProperties();
        SearchLlmProperties.Provider provider = new SearchLlmProperties.Provider();
        provider.setBaseUrl("https://provider.example");
        provider.setApiKey("provider-key");
        provider.setAuthHeader("Authorization");
        provider.setAuthPrefix("Bearer ");
        props.setProviders(Map.of("perplexity", provider));

        SearchLlmProperties.BackendConfig override = new SearchLlmProperties.BackendConfig();
        override.setBaseUrl("https://override.example");
        props.getFeatures().getWebSearch().setBackends(Map.of("perplexity", override));

        SearchLlmLocalConfigLoader loader = mock(SearchLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.empty());

        SearchLlmConfigResolver resolver = new SearchLlmConfigResolver(props, loader);
        SearchLlmConfigResolver.ResolvedBackendConfig resolved = resolver.resolveBackendConfig("perplexity");

        assertEquals("https://override.example", resolved.baseUrl());
        assertEquals("provider-key", resolved.apiKey());
        assertEquals("Authorization", resolved.authHeader());
        assertEquals("Bearer ", resolved.authPrefix());
    }

    @Test
    void current_shouldPreferLocalConfig() {
        SearchLlmProperties springProps = new SearchLlmProperties();
        springProps.getFeatures().getWebSearch().setDefaultPreset("spring");
        SearchLlmProperties localProps = new SearchLlmProperties();
        localProps.getFeatures().getWebSearch().setDefaultPreset("local");

        SearchLlmLocalConfigLoader loader = mock(SearchLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.of(localProps));

        SearchLlmConfigResolver resolver = new SearchLlmConfigResolver(springProps, loader);

        assertEquals("local", resolver.current().getFeatures().getWebSearch().getDefaultPreset());
    }
}

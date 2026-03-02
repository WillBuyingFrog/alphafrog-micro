package world.willfrog.externalinfo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.externalinfo.config.SearchLlmProperties;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class MarketNewsServiceTest {

    @Mock
    private SearchLlmLocalConfigLoader localConfigLoader;

    private SearchLlmProperties properties;
    private MarketNewsService service;

    @BeforeEach
    void setUp() {
        properties = new SearchLlmProperties();
        lenient().when(localConfigLoader.current()).thenReturn(Optional.empty());
        service = new MarketNewsService(new ObjectMapper(), properties, localConfigLoader);
    }

    @Test
    void buildPerplexityRequestBody_shouldIsolateProfileDomainsAndLanguages() {
        SearchLlmProperties.MarketNewsProfile cnProfile = new SearchLlmProperties.MarketNewsProfile();
        cnProfile.setName("cn");
        cnProfile.setQuery("今日A股");
        cnProfile.setIncludeDomains(List.of("sina.com.cn"));
        cnProfile.setExcludeDomains(List.of("example.com"));
        cnProfile.setLanguages(List.of("zh"));
        cnProfile.setCountry("CN");

        SearchLlmProperties.Provider provider = new SearchLlmProperties.Provider();
        provider.setBaseUrl("https://api.perplexity.ai");
        MarketNewsService.ProfileSearchOptions options = new MarketNewsService.ProfileSearchOptions(
                "perplexity",
                provider,
                "今日A股",
                5,
                OffsetDateTime.parse("2026-02-22T00:00:00+08:00"),
                OffsetDateTime.parse("2026-02-22T23:59:59+08:00"),
                List.of("zh"),
                List.of("sina.com.cn", "-example.com")
        );

        Map<String, Object> body = service.buildPerplexityRequestBody(cnProfile, options);

        assertEquals(List.of("sina.com.cn", "-example.com"), body.get("search_domain_filter"));
        assertEquals(List.of("zh"), body.get("search_language_filter"));
        assertEquals("CN", body.get("country"));
    }

    @Test
    void dedupeAndSort_shouldDedupeByUrlThenTitleAndSortByTimestampDesc() {
        List<MarketNewsService.MarketNewsItem> deduped = service.deduplicateAndSort(List.of(
                new MarketNewsService.MarketNewsItem("id-1", "2026-02-22T10:00:00+08:00", "同URL", "s1", "market", "https://a.com/n1"),
                new MarketNewsService.MarketNewsItem("id-2", "2026-02-22T11:00:00+08:00", "同URL", "s1", "market", "https://a.com/n1"),
                new MarketNewsService.MarketNewsItem("id-3", "2026-02-22T09:00:00+08:00", "重复标题", "s2", "market", "https://b.com/n2"),
                new MarketNewsService.MarketNewsItem("id-4", "2026-02-22T12:00:00+08:00", "重复标题", "s3", "market", "https://c.com/n3")
        ));

        assertEquals(2, deduped.size());
        assertEquals("id-4", deduped.get(0).id());
        assertEquals("id-2", deduped.get(1).id());
    }

    @Test
    void getTodayMarketNews_shouldAggregateMultipleProfilesAndApplyFinalLimit() {
        SearchLlmProperties cfg = buildMultiProfileConfig();
        lenient().when(localConfigLoader.current()).thenReturn(Optional.of(cfg));

        MarketNewsService testService = new MarketNewsService(new ObjectMapper(), properties, localConfigLoader) {
            @Override
            MarketNewsResponse executeProfileSearch(SearchLlmProperties.MarketNewsFeature feature,
                                                    SearchLlmProperties.MarketNewsProfile profile,
                                                    ProfileSearchOptions options) {
                if ("cn".equals(profile.getName())) {
                    return new MarketNewsResponse(List.of(
                            new MarketNewsItem("cn-1", "2026-02-22T09:00:00+08:00", "CN-1", "sina.com.cn", "market", "https://cn.com/1"),
                            new MarketNewsItem("dup-1", "2026-02-22T08:00:00+08:00", "重复", "sina.com.cn", "market", "https://dup.com/1")
                    ), "");
                }
                return new MarketNewsResponse(List.of(
                        new MarketNewsItem("us-1", "2026-02-22T11:00:00+08:00", "US-1", "reuters.com", "global", "https://us.com/1"),
                        new MarketNewsItem("dup-2", "2026-02-22T10:00:00+08:00", "重复", "reuters.com", "global", "https://dup.com/2")
                ), "");
            }
        };

        MarketNewsService.MarketNewsResult result = testService.getTodayMarketNews(
                new MarketNewsService.MarketNewsQuery("", List.of(), 2, "", "")
        );

        assertEquals(2, result.items().size());
        assertEquals("us-1", result.items().get(0).id());
        assertEquals("dup-2", result.items().get(1).id());
        assertEquals("mixed", result.provider());
    }

    @Test
    void getTodayMarketNews_shouldHonorRequestProviderOverride() {
        SearchLlmProperties cfg = buildMultiProfileConfig();
        lenient().when(localConfigLoader.current()).thenReturn(Optional.of(cfg));
        List<String> providersUsed = new ArrayList<>();

        MarketNewsService testService = new MarketNewsService(new ObjectMapper(), properties, localConfigLoader) {
            @Override
            MarketNewsResponse executeProfileSearch(SearchLlmProperties.MarketNewsFeature feature,
                                                    SearchLlmProperties.MarketNewsProfile profile,
                                                    ProfileSearchOptions options) {
                providersUsed.add(options.providerName());
                return new MarketNewsResponse(List.of(), "");
            }
        };

        MarketNewsService.MarketNewsResult result = testService.getTodayMarketNews(
                new MarketNewsService.MarketNewsQuery("perplexity", List.of(), 2, "", "")
        );

        assertEquals(List.of("perplexity", "perplexity"), providersUsed);
        assertEquals("perplexity", result.provider());
    }

    @Test
    void timeoutResolution_shouldRespectProviderOverrideAndFallback() {
        SearchLlmProperties.Provider provider = new SearchLlmProperties.Provider();
        provider.setConnectTimeoutSeconds(6);
        provider.setRequestTimeoutSeconds(12);

        assertEquals(6, service.connectTimeout(provider));
        assertEquals(12, service.requestTimeout(provider));

        provider.setConnectTimeoutSeconds(0);
        provider.setRequestTimeoutSeconds(-1);
        assertEquals(20, service.connectTimeout(provider));
        assertEquals(45, service.requestTimeout(provider));
        assertEquals(20, service.connectTimeout(null));
        assertEquals(45, service.requestTimeout(null));
    }

    private SearchLlmProperties buildMultiProfileConfig() {
        SearchLlmProperties cfg = new SearchLlmProperties();
        Map<String, SearchLlmProperties.Provider> providers = new LinkedHashMap<>();
        SearchLlmProperties.Provider exa = new SearchLlmProperties.Provider();
        exa.setBaseUrl("https://api.exa.ai");
        providers.put("exa", exa);
        SearchLlmProperties.Provider perplexity = new SearchLlmProperties.Provider();
        perplexity.setBaseUrl("https://api.perplexity.ai");
        providers.put("perplexity", perplexity);
        cfg.setProviders(providers);

        SearchLlmProperties.MarketNewsFeature marketNews = new SearchLlmProperties.MarketNewsFeature();
        marketNews.setDefaultProvider("exa");
        marketNews.setDefaultLimit(8);

        SearchLlmProperties.MarketNewsProfile cn = new SearchLlmProperties.MarketNewsProfile();
        cn.setName("cn");
        cn.setProvider("exa");
        cn.setQuery("cn");
        cn.setIncludeDomains(List.of("sina.com.cn"));

        SearchLlmProperties.MarketNewsProfile us = new SearchLlmProperties.MarketNewsProfile();
        us.setName("us");
        us.setProvider("perplexity");
        us.setQuery("us");
        us.setIncludeDomains(List.of("reuters.com"));

        marketNews.setProfiles(List.of(cn, us));
        SearchLlmProperties.Features features = new SearchLlmProperties.Features();
        features.setMarketNews(marketNews);
        cfg.setFeatures(features);
        return cfg;
    }
}

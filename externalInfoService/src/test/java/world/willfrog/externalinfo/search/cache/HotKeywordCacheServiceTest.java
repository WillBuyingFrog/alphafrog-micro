package world.willfrog.externalinfo.search.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchBackendMeta;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchCitation;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchHit;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchResponse;
import world.willfrog.externalinfo.retrieval.EmbeddingApiClient;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotKeywordCacheServiceTest {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void findCluster_shouldRequireEmbeddingSimilarityAndReturnFullResponse() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations valueOps = mock(ValueOperations.class);
        HashOperations hashOps = mock(HashOperations.class);
        SetOperations setOps = mock(SetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(120L);

        EmbeddingApiClient embeddingApiClient = mock(EmbeddingApiClient.class);
        when(embeddingApiClient.embed("茅台去年新闻")).thenReturn(List.of(1f, 0f));
        HotKeywordCacheService service = new HotKeywordCacheService(redisTemplate, new ObjectMapper(), new AnswerAggregator(), embeddingApiClient);

        AtomicReference<String> payload = new AtomicReference<>();
        AtomicInteger getCount = new AtomicInteger();
        when(hashOps.get(anyString(), eq("payload"))).thenAnswer(inv -> getCount.getAndIncrement() == 0 ? null : payload.get());
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        service.writeCluster("intent=news|slots=asset=茅台|time_bucket=2025全年",
                "查{asset}{time_range}新闻",
                "查{asset}{time_range}新闻",
                "茅台去年新闻",
                "news",
                WebSearchResponse.newBuilder()
                        .setOk(true)
                        .addHits(WebSearchHit.newBuilder().setTitle("t").setUrl("https://example.com").setSnippet("s").build())
                        .addCitations(WebSearchCitation.newBuilder().setIndex(1).setUrl("https://example.com").setTitle("t").build())
                        .setBackendMeta(WebSearchBackendMeta.newBuilder().setBackend("exa").setModelOrStrength("fast").build())
                        .setAnswer("answer")
                        .setCanonicalQuery("查{asset}{time_range}新闻")
                        .setSlotSignature("asset=茅台|time_range=2025全年|numeric=|market=")
                        .setResultHash("hash")
                        .build(),
                3600);

        verify(hashOps).put(anyString(), eq("payload"), payloadCaptor.capture());
        payload.set(String.valueOf(payloadCaptor.getValue()));

        HotKeywordCacheService.HotKeywordCacheResult hit = service.findCluster(
                "intent=news|slots=asset=茅台|time_bucket=2025全年", "茅台去年新闻");

        assertNotNull(hit);
        assertEquals("answer", hit.response().getAnswer());
        assertEquals("https://example.com", hit.response().getHits(0).getUrl());
        assertEquals("hash", hit.response().getResultHash());

        when(embeddingApiClient.embed("茅台去年财报")).thenReturn(List.of(0f, 1f));
        assertNull(service.findCluster("intent=news|slots=asset=茅台|time_bucket=2025全年", "茅台去年财报"));
    }
}

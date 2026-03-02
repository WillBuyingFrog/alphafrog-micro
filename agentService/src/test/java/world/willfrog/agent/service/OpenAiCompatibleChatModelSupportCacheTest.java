package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleChatModelSupportCacheTest {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatModelSupportCacheTest.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractCachedTokens_openRouterFormat() {
        String body = """
                {
                  "usage": {
                    "prompt_tokens": 10339,
                    "completion_tokens": 60,
                    "total_tokens": 10399,
                    "prompt_tokens_details": {
                      "cached_tokens": 10318
                    }
                  }
                }
                """;
        RawHttpLogger.HttpResponseRecord response = RawHttpLogger.HttpResponseRecord.builder()
                .body(body).statusCode(200).build();

        Integer cached = OpenAiCompatibleChatModelSupport.extractCachedTokensFromResponse(objectMapper, response, log);
        assertNotNull(cached);
        assertEquals(10318, cached);
    }

    @Test
    void extractCachedTokens_dashScopeFormat() {
        String body = """
                {
                  "usage": {
                    "prompt_tokens": 5000,
                    "completion_tokens": 100,
                    "total_tokens": 5100,
                    "prompt_tokens_details": {
                      "cached_tokens": 4500
                    }
                  }
                }
                """;
        RawHttpLogger.HttpResponseRecord response = RawHttpLogger.HttpResponseRecord.builder()
                .body(body).statusCode(200).build();

        Integer cached = OpenAiCompatibleChatModelSupport.extractCachedTokensFromResponse(objectMapper, response, log);
        assertNotNull(cached);
        assertEquals(4500, cached);
    }

    @Test
    void extractCachedTokens_noCacheDetails() {
        String body = """
                {
                  "usage": {
                    "prompt_tokens": 1000,
                    "completion_tokens": 200,
                    "total_tokens": 1200
                  }
                }
                """;
        RawHttpLogger.HttpResponseRecord response = RawHttpLogger.HttpResponseRecord.builder()
                .body(body).statusCode(200).build();

        Integer cached = OpenAiCompatibleChatModelSupport.extractCachedTokensFromResponse(objectMapper, response, log);
        assertNull(cached);
    }

    @Test
    void extractCachedTokens_nullResponse() {
        Integer cached = OpenAiCompatibleChatModelSupport.extractCachedTokensFromResponse(objectMapper, null, log);
        assertNull(cached);
    }

    @Test
    void extractCachedTokens_emptyBody() {
        RawHttpLogger.HttpResponseRecord response = RawHttpLogger.HttpResponseRecord.builder()
                .body("").statusCode(200).build();

        Integer cached = OpenAiCompatibleChatModelSupport.extractCachedTokensFromResponse(objectMapper, response, log);
        assertNull(cached);
    }

    @Test
    void extractCachedTokens_invalidJson() {
        RawHttpLogger.HttpResponseRecord response = RawHttpLogger.HttpResponseRecord.builder()
                .body("not valid json").statusCode(200).build();

        Integer cached = OpenAiCompatibleChatModelSupport.extractCachedTokensFromResponse(objectMapper, response, log);
        assertNull(cached);
    }

    @Test
    void extractCachedTokens_zeroCachedTokens() {
        String body = """
                {
                  "usage": {
                    "prompt_tokens": 1000,
                    "completion_tokens": 200,
                    "total_tokens": 1200,
                    "prompt_tokens_details": {
                      "cached_tokens": 0
                    }
                  }
                }
                """;
        RawHttpLogger.HttpResponseRecord response = RawHttpLogger.HttpResponseRecord.builder()
                .body(body).statusCode(200).build();

        Integer cached = OpenAiCompatibleChatModelSupport.extractCachedTokensFromResponse(objectMapper, response, log);
        assertNotNull(cached);
        assertEquals(0, cached);
    }

    @Test
    void extractCachedTokens_fireworksFormat() {
        // Fireworks: perf_metrics.cached_prompt_tokens
        String body = """
                {
                  "usage": {
                    "prompt_tokens": 1000,
                    "completion_tokens": 200,
                    "total_tokens": 1200
                  },
                  "perf_metrics": {
                    "cached_prompt_tokens": 800,
                    "tokens_per_second": 123.45
                  }
                }
                """;
        RawHttpLogger.HttpResponseRecord response = RawHttpLogger.HttpResponseRecord.builder()
                .body(body).statusCode(200).build();

        Integer cached = OpenAiCompatibleChatModelSupport.extractCachedTokensFromResponse(objectMapper, response, log);
        assertNotNull(cached);
        assertEquals(800, cached);
    }

    @Test
    void extractCachedTokens_fireworksZeroCached() {
        String body = """
                {
                  "usage": {
                    "prompt_tokens": 1000,
                    "completion_tokens": 200,
                    "total_tokens": 1200
                  },
                  "perf_metrics": {
                    "cached_prompt_tokens": 0
                  }
                }
                """;
        RawHttpLogger.HttpResponseRecord response = RawHttpLogger.HttpResponseRecord.builder()
                .body(body).statusCode(200).build();

        Integer cached = OpenAiCompatibleChatModelSupport.extractCachedTokensFromResponse(objectMapper, response, log);
        assertNotNull(cached);
        assertEquals(0, cached);
    }

    @Test
    void extractCachedTokens_fireworksNoPerfMetrics() {
        String body = """
                {
                  "usage": {
                    "prompt_tokens": 1000,
                    "completion_tokens": 200,
                    "total_tokens": 1200
                  }
                }
                """;
        RawHttpLogger.HttpResponseRecord response = RawHttpLogger.HttpResponseRecord.builder()
                .body(body).statusCode(200).build();

        Integer cached = OpenAiCompatibleChatModelSupport.extractCachedTokensFromResponse(objectMapper, response, log);
        assertNull(cached);
    }
}

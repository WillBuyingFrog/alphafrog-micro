package world.willfrog.externalinfo.search.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRequest;
import world.willfrog.externalinfo.search.SearchLlmConfigResolver;
import world.willfrog.externalinfo.search.WebSearchExecutionContext;
import world.willfrog.externalinfo.search.profile.GlobalUserProfileInjector;
import world.willfrog.externalinfo.search.profile.ProfileContext;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerplexityBackendTest {

    @Test
    void search_shouldUseSonarPathAndParseSearchResults() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/sonar", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"choices":[{"message":{"content":"answer"}}],
                     "search_results":[{"title":"Title","url":"https://example.com/a","snippet":"Snippet","date":"2026-04-01","source":"example.com"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();
        try {
            PerplexityBackend backend = new PerplexityBackend(mapper, new GlobalUserProfileInjector(), new ProfileContext());
            BackendSearchResult result = backend.search(new WebSearchExecutionContext(
                    WebSearchRequest.newBuilder()
                            .setQuery("q")
                            .setTimeRangeStart("2026-04-01T00:00:00Z")
                            .setTimeRangeEnd("2026-04-02T00:00:00Z")
                            .build(),
                    "perplexity",
                    "news",
                    "reasoning",
                    7,
                    "",
                    List.of("example.com"),
                    List.of(),
                    null,
                    new SearchLlmConfigResolver.ResolvedBackendConfig(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "key",
                            "Authorization",
                            "Bearer ",
                            Map.of(),
                            2,
                            5)
            ));

            JsonNode sent = mapper.readTree(body.get());
            assertEquals("/v1/sonar", path.get());
            assertEquals("sonar-reasoning-pro", sent.path("model").asText());
            assertEquals(7, sent.path("max_search_results").asInt());
            assertEquals("4/1/2026", sent.path("search_after_date_filter").asText());
            assertEquals("example.com", sent.path("search_domain_filter").get(0).asText());
            assertTrue(result.ok());
            assertEquals("Title", result.hits().get(0).title());
            assertEquals("Snippet", result.hits().get(0).snippet());
            assertEquals("answer", result.answer());
        } finally {
            server.stop(0);
        }
    }
}

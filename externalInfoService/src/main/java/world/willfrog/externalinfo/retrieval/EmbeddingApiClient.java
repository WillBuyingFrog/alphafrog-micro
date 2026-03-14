package world.willfrog.externalinfo.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class EmbeddingApiClient {

    @Value("${alphafrog.rag.embedding.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${alphafrog.rag.embedding.api-key:}")
    private String apiKey;

    @Value("${alphafrog.rag.embedding.model:text-embedding-3-small}")
    private String model;

    @Value("${alphafrog.rag.embedding.dimensions:1024}")
    private int dimensions;

    private final RestClient restClient;

    public EmbeddingApiClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    /**
     * 将文本向量化（OpenAI 兼容接口）。
     * 必须与 P7-03 ingestion 脚本使用相同模型和维度，否则检索结果错误。
     */
    @SuppressWarnings("unchecked")
    public List<Float> embed(String text) {
        Map<String, Object> body = Map.of(
                "model", model,
                "input", text,
                "dimensions", dimensions
        );
        Map<?, ?> response = restClient.post()
                .uri(baseUrl + "/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null || !response.containsKey("data")) {
            throw new IllegalStateException("Embedding API returned invalid response: missing 'data' field");
        }
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        if (data == null || data.isEmpty()) {
            throw new IllegalStateException("Embedding API returned empty data array");
        }
        List<Double> raw = (List<Double>) data.get(0).get("embedding");
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("Embedding API returned empty embedding vector");
        }
        return raw.stream().map(Double::floatValue).toList();
    }
}

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

    // 来自 application.yml / 环境变量的默认值（热加载 JSON 未覆盖时使用）
    @Value("${alphafrog.rag.embedding.base-url:https://api.openai.com/v1}")
    private String defaultBaseUrl;

    @Value("${alphafrog.rag.embedding.api-key:}")
    private String defaultApiKey;

    @Value("${alphafrog.rag.embedding.model:text-embedding-3-small}")
    private String defaultModel;

    /** 必须与 P7-03 ingestion 脚本使用的维度一致；默认使用压缩后的 1024 维 */
    @Value("${alphafrog.rag.embedding.dimensions:1024}")
    private int defaultDimensions;

    private final RestClient restClient;
    private final RagEmbeddingLocalConfigLoader localConfigLoader;

    public EmbeddingApiClient(RestClient.Builder builder, RagEmbeddingLocalConfigLoader localConfigLoader) {
        this.restClient = builder.build();
        this.localConfigLoader = localConfigLoader;
    }

    /**
     * 将文本向量化（OpenAI 兼容接口）。
     *
     * <p>配置优先级（从高到低）：
     * <ol>
     *   <li>rag-embedding.local.json（热加载，运行时可修改）</li>
     *   <li>application.yml / 环境变量（AF_EMBEDDING_BASE_URL 等）</li>
     * </ol>
     *
     * <p>必须与 P7-03 ingestion 脚本使用相同模型和维度，否则检索结果错误。
     */
    @SuppressWarnings("unchecked")
    public List<Float> embed(String text) {
        // 每次调用时动态解析配置，支持热加载
        RagEmbeddingProperties localCfg = localConfigLoader.current().orElse(null);

        String baseUrl = resolve(localCfg != null ? localCfg.getBaseUrl() : null, defaultBaseUrl);
        String apiKey = resolve(localCfg != null ? localCfg.getApiKey() : null, defaultApiKey);
        String model = resolve(localCfg != null ? localCfg.getModel() : null, defaultModel);
        int dimensions = (localCfg != null && localCfg.getDimensions() > 0)
                ? localCfg.getDimensions() : defaultDimensions;

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

    /** 若本地配置值非空则使用，否则回退到 fallback。 */
    private static String resolve(String localValue, String fallback) {
        return (localValue != null && !localValue.isBlank()) ? localValue : fallback;
    }
}

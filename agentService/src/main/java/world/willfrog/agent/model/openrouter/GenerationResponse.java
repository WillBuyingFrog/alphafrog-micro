package world.willfrog.agent.model.openrouter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * OpenRouter Generation API 响应结构。
 *
 * <p>对应 GET /api/v1/generation?id={gen_id} 的返回值。</p>
 *
 * @see <a href="https://openrouter.ai/docs/api/api-reference/generations/get-generation">OpenRouter Generation API</a>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GenerationResponse {

    private GenerationData data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GenerationData {
        @JsonProperty("total_cost")
        private Double totalCost;

        @JsonProperty("upstream_inference_cost")
        private Double upstreamInferenceCost;

        @JsonProperty("cache_discount")
        private Double cacheDiscount;

        @JsonProperty("is_byok")
        private Boolean isByok;
    }
}

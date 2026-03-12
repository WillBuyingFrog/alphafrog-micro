package world.willfrog.alphafrogmicro.frontend.model.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TraceDetailResponse {
    private String type;
    private String traceId;
    private String phase;
    private String todoId;
    private Integer todoSequence;
    private String time;
    private Long durationMs;

    // LLM specific
    private String model;
    private String endpoint;
    private Long inputTokens;
    private Long outputTokens;
    private Integer cachedTokens;
    private Double actualCost;
    private Object inputMessages;
    private String outputText;
    private String reasoningText;
    private Boolean hasError;
    private String error;

    // Tool specific
    private String toolName;
    private Map<String, Object> params;
    private String output;
    private Boolean success;
    private Boolean cacheHit;
    private String cacheKey;
    private String decisionLlmTraceId;
    private String decisionExcerpt;
}

package world.willfrog.alphafrogmicro.frontend.model.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TraceSpanItem {
    private String type;          // "llm" or "tool"
    private String traceId;
    private int seq;
    private String time;
    private String phase;
    private String todoId;
    private Long durationMs;

    // LLM specific
    private String model;
    private Long inputTokens;
    private Long outputTokens;
    private Boolean hasError;
    private Boolean hasInputMessages;
    private Boolean hasReasoning;
    private String outputSummary;

    // Tool specific
    private String toolName;
    private Boolean success;
    private Boolean cacheHit;
    private String decisionLlmTraceId;
}

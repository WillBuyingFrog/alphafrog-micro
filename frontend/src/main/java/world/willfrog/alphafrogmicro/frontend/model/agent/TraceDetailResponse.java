package world.willfrog.alphafrogmicro.frontend.model.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceDetailResponse(
        String type,
        String traceId,
        String phase,
        String todoId,
        Integer todoSequence,
        String time,
        Long durationMs,

        // LLM specific
        String model,
        String endpoint,
        Long inputTokens,
        Long outputTokens,
        Integer cachedTokens,
        Double actualCost,
        Object inputMessages,
        String outputText,
        String reasoningText,
        Boolean hasError,
        String error,

        // Tool specific
        String toolName,
        Map<String, Object> params,
        String output,
        Boolean success,
        Boolean cacheHit,
        String cacheKey,
        String decisionLlmTraceId,
        String decisionExcerpt
) {
}

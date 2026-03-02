package world.willfrog.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.InternalOpenAiHelper;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

final class OpenAiCompatibleChatModelSupport {

    private OpenAiCompatibleChatModelSupport() {
    }

    static TokenUsage extractTokenUsageFromResponse(ObjectMapper objectMapper,
                                                    RawHttpLogger.HttpResponseRecord response,
                                                    Logger log) {
        if (response == null || response.getBody() == null || response.getBody().isBlank()) {
            return null;
        }

        try {
            Map<String, Object> json = objectMapper.readValue(response.getBody(), new TypeReference<>() {
            });
            Object usage = json.get("usage");
            if (usage instanceof Map<?, ?> usageMap) {
                Integer promptTokens = toInt(usageMap.get("prompt_tokens"));
                Integer completionTokens = toInt(usageMap.get("completion_tokens"));
                Integer totalTokens = toInt(usageMap.get("total_tokens"));
                int total = totalTokens != null ? totalTokens
                        : ((promptTokens != null ? promptTokens : 0) + (completionTokens != null ? completionTokens : 0));

                return new TokenUsage(
                        promptTokens != null ? promptTokens : 0,
                        completionTokens != null ? completionTokens : 0,
                        total
                );
            }
        } catch (Exception e) {
            log.debug("Failed to extract token usage from response: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 从 LLM 响应中提取 cached tokens。
     *
     * <p>支持以下格式：</p>
     * <ul>
     *   <li>OpenRouter / Dashscope: {@code usage.prompt_tokens_details.cached_tokens}</li>
     *   <li>Fireworks: {@code perf_metrics.cached_prompt_tokens}</li>
     * </ul>
     *
     * @param objectMapper Jackson ObjectMapper
     * @param response     原始 HTTP 响应
     * @param log          日志
     * @return cached tokens 数量，如果不存在则返回 null
     */
    static Integer extractCachedTokensFromResponse(ObjectMapper objectMapper,
                                                   RawHttpLogger.HttpResponseRecord response,
                                                   Logger log) {
        if (response == null || response.getBody() == null || response.getBody().isBlank()) {
            return null;
        }

        try {
            Map<String, Object> json = objectMapper.readValue(response.getBody(), new TypeReference<>() {
            });
            Object usage = json.get("usage");
            if (usage instanceof Map<?, ?> usageMap) {
                // OpenRouter / Dashscope: usage.prompt_tokens_details.cached_tokens
                Object promptTokensDetails = usageMap.get("prompt_tokens_details");
                if (promptTokensDetails instanceof Map<?, ?> detailsMap) {
                    Integer cached = toInt(detailsMap.get("cached_tokens"));
                    if (cached != null) {
                        return cached;
                    }
                }
            }
            
            // Fireworks: perf_metrics.cached_prompt_tokens
            Object perfMetrics = json.get("perf_metrics");
            if (perfMetrics instanceof Map<?, ?> perfMap) {
                Integer cached = toInt(perfMap.get("cached_prompt_tokens"));
                if (cached != null) {
                    return cached;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract cached tokens from response: {}", e.getMessage());
        }

        return null;
    }

    static Map<String, String> buildRequestHeaders(String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + nvl(apiKey));
        return headers;
    }

    static FinishReason extractFinishReason(ChatCompletionResponse completion) {
        if (completion == null || completion.choices() == null || completion.choices().isEmpty()) {
            return null;
        }
        String raw = completion.choices().get(0).finishReason();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return InternalOpenAiHelper.finishReasonFrom(raw);
        } catch (Exception e) {
            return null;
        }
    }

    static String nvl(String value) {
        return value == null ? "" : value;
    }

    static String buildChatCompletionsUrl(String baseUrl) {
        String normalized = nvl(baseUrl).trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        if (normalized.endsWith("/v1")) {
            return normalized + "/chat/completions";
        }
        return normalized + "/v1/chat/completions";
    }

    static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ');
        if (normalized.length() <= 600) {
            return normalized;
        }
        return normalized.substring(0, 600) + "...";
    }

    private static Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }
}

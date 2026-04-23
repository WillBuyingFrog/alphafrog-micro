package world.willfrog.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.OpenAiUtils;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OpenAiCompatibleChatModelSupport {

    private OpenAiCompatibleChatModelSupport() {
    }

    /**
     * SSE 流聚合结果。
     */
    record SseAggregateResult(
            String content,
            String reasoningContent,
            ChatCompletionResponse completionResponse,
            StreamingProgressTracker.StreamingProgressSnapshot progressSnapshot
    ) {
    }

    /**
     * 解析 SSE 流并聚合为完整的 ChatCompletionResponse。
     *
     * @param inputStream     SSE 流输入
     * @param objectMapper    Jackson ObjectMapper
     * @param log             日志
     * @param progressTracker 实时进度追踪器
     * @return 聚合结果（包含 content、reasoningContent、合成的 ChatCompletionResponse、进度快照）
     */
    static SseAggregateResult aggregateSseStream(
            InputStream inputStream,
            ObjectMapper objectMapper,
            Logger log,
            StreamingProgressTracker progressTracker) {

        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();

        Map<String, Object> lastUsage = null;
        String lastFinishReason = null;
        String lastId = null;
        String lastModel = null;
        long lastCreated = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                // OpenRouter keep-alive 注释行，跳过
                if (line.startsWith(": ")) {
                    continue;
                }
                if (!line.startsWith("data: ")) {
                    continue;
                }
                String data = line.substring("data: ".length()).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }

                Map<String, Object> chunk;
                try {
                    chunk = objectMapper.readValue(data, new TypeReference<>() {
                    });
                } catch (Exception e) {
                    log.debug("SSE chunk JSON 解析失败，跳过: {}", e.getMessage());
                    continue;
                }

                if (isSseErrorChunk(chunk)) {
                    throw new IllegalStateException("SSE 流中收到错误 chunk: " + data);
                }

                // 提取顶层字段
                if (chunk.get("id") instanceof String id) {
                    lastId = id;
                }
                if (chunk.get("model") instanceof String model) {
                    lastModel = model;
                }
                if (chunk.get("created") instanceof Number created) {
                    lastCreated = created.longValue();
                }
                if (chunk.get("usage") instanceof Map<?, ?> usageMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> safeUsage = (Map<String, Object>) usageMap;
                    lastUsage = safeUsage;
                }

                // 提取 choices
                Object choicesObj = chunk.get("choices");
                if (!(choicesObj instanceof List<?> choicesList) || choicesList.isEmpty()) {
                    continue;
                }

                Object firstChoice = choicesList.get(0);
                if (!(firstChoice instanceof Map<?, ?> choiceMap)) {
                    continue;
                }

                // finish_reason
                Object finishReason = choiceMap.get("finish_reason");
                if (finishReason instanceof String fr && !fr.isBlank() && !"null".equals(fr)) {
                    lastFinishReason = fr;
                }

                // delta
                Object deltaObj = choiceMap.get("delta");
                if (!(deltaObj instanceof Map<?, ?> deltaMap)) {
                    continue;
                }

                String deltaContent = null;
                String deltaReasoning = null;

                Object contentObj = deltaMap.get("content");
                if (contentObj instanceof String s) {
                    deltaContent = s;
                    contentBuilder.append(s);
                }

                Object reasoningObj = deltaMap.get("reasoning_content");
                if (reasoningObj instanceof String s) {
                    deltaReasoning = s;
                    reasoningBuilder.append(s);
                }

                // OpenRouter 标准字段：delta.reasoning（与 reasoning_content 互为别名）
                if (deltaReasoning == null) {
                    Object reasoningAlias = deltaMap.get("reasoning");
                    if (reasoningAlias instanceof String s2) {
                        deltaReasoning = s2;
                        reasoningBuilder.append(s2);
                    }
                }

                // OpenRouter 高级字段：delta.reasoning_details 数组
                if (deltaReasoning == null) {
                    Object detailsObj = deltaMap.get("reasoning_details");
                    if (detailsObj instanceof List<?> detailsList && !detailsList.isEmpty()) {
                        StringBuilder detailsText = new StringBuilder();
                        for (Object detail : detailsList) {
                            if (detail instanceof Map<?, ?> detailMap) {
                                Object text = detailMap.get("text");
                                if (text instanceof String ts) {
                                    detailsText.append(ts);
                                    continue;
                                }
                                Object summary = detailMap.get("summary");
                                if (summary instanceof String ss) {
                                    detailsText.append(ss);
                                }
                            }
                        }
                        if (detailsText.length() > 0) {
                            deltaReasoning = detailsText.toString();
                            reasoningBuilder.append(deltaReasoning);
                        }
                    }
                }

                if (progressTracker != null) {
                    progressTracker.onChunkReceived(deltaContent, deltaReasoning);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("SSE 流读取失败", e);
        }

        // 构造合成的 ChatCompletionResponse
        // 使用 Map 构造 JSON 然后反序列化，避免直接操作 langchain4j 内部类
        Map<String, Object> syntheticResponse = new LinkedHashMap<>();
        syntheticResponse.put("id", lastId != null ? lastId : "");
        syntheticResponse.put("object", "chat.completion");
        syntheticResponse.put("created", lastCreated > 0 ? lastCreated : System.currentTimeMillis() / 1000);
        syntheticResponse.put("model", lastModel != null ? lastModel : "");

        Map<String, Object> messageMap = new LinkedHashMap<>();
        messageMap.put("role", "assistant");
        messageMap.put("content", contentBuilder.toString());

        Map<String, Object> choiceMap = new LinkedHashMap<>();
        choiceMap.put("index", 0);
        choiceMap.put("message", messageMap);
        choiceMap.put("finish_reason", lastFinishReason != null ? lastFinishReason : "stop");

        syntheticResponse.put("choices", List.of(choiceMap));

        if (lastUsage != null) {
            syntheticResponse.put("usage", lastUsage);
        }

        ChatCompletionResponse completion;
        try {
            completion = objectMapper.readValue(
                    objectMapper.writeValueAsString(syntheticResponse),
                    ChatCompletionResponse.class
            );
        } catch (Exception e) {
            throw new IllegalStateException("SSE 聚合结果反序列化失败", e);
        }

        StreamingProgressTracker.StreamingProgressSnapshot snapshot = progressTracker != null
                ? progressTracker.getSnapshot()
                : new StreamingProgressTracker.StreamingProgressSnapshot(0, 0, 0, 0, 0.0);

        return new SseAggregateResult(
                contentBuilder.toString(),
                reasoningBuilder.toString(),
                completion,
                snapshot
        );
    }

    private static boolean isSseErrorChunk(Map<String, Object> chunk) {
        if (chunk == null) {
            return false;
        }
        if (chunk.containsKey("error")) {
            return true;
        }
        Object choices = chunk.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> choice) {
                Object finishReason = choice.get("finish_reason");
                if ("error".equals(finishReason)) {
                    return true;
                }
            }
        }
        return false;
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
            return OpenAiUtils.finishReasonFrom(raw);
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

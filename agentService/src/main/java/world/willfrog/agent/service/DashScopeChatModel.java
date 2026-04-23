package world.willfrog.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.openai.internal.OpenAiUtils;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import world.willfrog.agent.context.AgentContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DashScope OpenAI 兼容 ChatModel 实现 (ALP-28)
 *
 * <p>与 OpenRouterProviderRoutedChatModel 分离，避免 provider 路由冲突。</p>
 * <p>默认启用流式输出（stream=true），内部聚合 SSE 流为完整响应。</p>
 * <p>支持 thinking 模式开关（enable_thinking），默认开启。</p>
 */
@RequiredArgsConstructor
@Slf4j
public class DashScopeChatModel implements ChatModel {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private static final int DEFAULT_THINKING_BUDGET = 38912;

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Double temperature;
    private final Integer maxTokens;
    private final RawHttpLogger httpLogger;
    private final AgentObservabilityService observabilityService;
    private final String endpointName;
    private final boolean enableThinking;
    private final AgentLlmLocalConfigLoader localConfigLoader;

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        List<ChatMessage> messages = chatRequest.messages();
        List<ToolSpecification> toolSpecifications = chatRequest.toolSpecifications();
        String requestJson = null;
        long requestStartedAt = System.currentTimeMillis();

        boolean clientWantsCapture = observabilityService != null
                && observabilityService.isCaptureLlmRequestsEnabled(AgentContext.getRunId());
        boolean endpointAllowed = httpLogger != null && httpLogger.shouldCapture(endpointName);
        boolean shouldCapture = clientWantsCapture && endpointAllowed;
        RawHttpLogger.HttpRequestRecord requestRecord = null;
        RawHttpLogger.HttpResponseRecord responseRecord = null;
        String curlCommand = null;

        try {
            ChatCompletionRequest.Builder builder = ChatCompletionRequest.builder()
                    .model(OpenAiCompatibleChatModelSupport.nvl(modelName))
                    .messages(OpenAiUtils.toOpenAiMessages(messages == null ? List.of() : messages))
                    .temperature(temperature)
                    .maxCompletionTokens(maxTokens);

            if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
                builder.tools(OpenAiUtils.toTools(toolSpecifications, false));
            }

            ChatCompletionRequest request = builder.build();
            Map<String, Object> requestJsonMap = objectMapper.convertValue(
                    request,
                    new TypeReference<Map<String, Object>>() {
                    }
            );

            // 默认启用流式输出
            boolean useStream = true;
            // DashScope 不支持 tools + stream 同时使用
            if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
                useStream = false;
            }
            requestJsonMap.put("stream", useStream);
            if (useStream) {
                requestJsonMap.put("stream_options", Map.of("include_usage", true));
            }

            AgentContext.StructuredOutputSpec structuredOutputSpec = AgentContext.getStructuredOutputSpec();
            if (structuredOutputSpec != null) {
                requestJsonMap.put("response_format", structuredOutputSpec.asResponseFormat());
            }

            // DashScope 深度思考模型多数仅支持流式输出，stream=false 时不应开启 thinking
            if (useStream) {
                applyThinkingConfig(requestJsonMap, messages);
            }

            requestJson = objectMapper.writeValueAsString(requestJsonMap);
            String requestUrl = OpenAiCompatibleChatModelSupport.buildChatCompletionsUrl(baseUrl);
            Map<String, String> requestHeaders = OpenAiCompatibleChatModelSupport.buildRequestHeaders(apiKey);

            HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + OpenAiCompatibleChatModelSupport.nvl(apiKey))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));

            if (shouldCapture) {
                requestRecord = httpLogger.recordRequest(requestUrl, "POST", requestHeaders, requestJson);
            }

            // Debug curl 日志（热加载配置）
            if (isDebugCurlEnabled()) {
                curlCommand = buildCurlCommand(requestUrl, requestHeaders, requestJson);
                log.info("[LLM Debug CURL] endpoint={} model={}\n{}", endpointName, modelName, curlCommand);
            }

            HttpResponse<java.io.InputStream> httpResponse = HTTP_CLIENT.send(
                    httpRequestBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            int statusCode = httpResponse.statusCode();
            long durationMs = System.currentTimeMillis() - requestStartedAt;

            ChatCompletionResponse completion;
            String reasoningContent = null;
            StreamingProgressTracker.StreamingProgressSnapshot progressSnapshot = null;

            if (useStream && statusCode >= 200 && statusCode < 300) {
                // 流式响应：解析 SSE
                StreamingProgressTracker tracker = new StreamingProgressTracker(log, modelName, endpointName);
                OpenAiCompatibleChatModelSupport.SseAggregateResult aggregateResult =
                        OpenAiCompatibleChatModelSupport.aggregateSseStream(
                                httpResponse.body(), objectMapper, log, tracker
                        );
                tracker.onStreamComplete(durationMs);
                completion = aggregateResult.completionResponse();
                reasoningContent = aggregateResult.reasoningContent();
                progressSnapshot = aggregateResult.progressSnapshot();

                // 为了 HTTP 捕获，将聚合后的响应体序列化
                String aggregatedBody = objectMapper.writeValueAsString(
                        objectMapper.convertValue(completion, new TypeReference<Map<String, Object>>() {
                        })
                );
                if (shouldCapture) {
                    Map<String, String> responseHeaders = new java.util.HashMap<>();
                    responseHeaders.put("Content-Type", "application/json");
                    responseRecord = httpLogger.recordResponse(statusCode, responseHeaders, aggregatedBody, durationMs);
                    curlCommand = httpLogger.toCurlCommand(requestRecord);
                }
            } else {
                // 非流式响应或错误：读取完整 body
                String responseJson;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(httpResponse.body(), StandardCharsets.UTF_8))) {
                    responseJson = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                }

                if (shouldCapture) {
                    Map<String, String> responseHeaders = httpLogger.extractHeaders(httpResponse);
                    responseRecord = httpLogger.recordResponse(statusCode, responseHeaders, responseJson, durationMs);
                    curlCommand = httpLogger.toCurlCommand(requestRecord);
                }

                if (statusCode < 200 || statusCode >= 300) {
                    if (shouldCapture && observabilityService != null) {
                        reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs,
                                "HTTP_ERROR_" + statusCode, null, null);
                    }
                    String detail = "DashScope chat completion failed"
                            + " (http=" + statusCode
                            + ", model=" + OpenAiCompatibleChatModelSupport.nvl(modelName)
                            + ", error=" + OpenAiCompatibleChatModelSupport.shorten(responseJson)
                            + ", request=" + OpenAiCompatibleChatModelSupport.shorten(requestJson) + ")";
                    log.warn(detail);
                    throw new IllegalStateException(detail);
                }

                completion = objectMapper.readValue(responseJson, ChatCompletionResponse.class);
                // 非流式响应也可能包含 reasoning_content
                reasoningContent = extractReasoningContentFromResponse(responseJson);
            }

            AiMessage aiMessage = OpenAiUtils.aiMessageFrom(completion);

            // 从 reasoningContent 或 <think> 标签提取 thinking
            String finalThinking = reasoningContent;
            if (finalThinking == null || finalThinking.isBlank()) {
                ThinkingContent thinking = extractThinkingContent(aiMessage == null ? null : aiMessage.text());
                if (thinking.hasThinking()) {
                    finalThinking = thinking.thinking();
                    if (aiMessage != null && thinking.hasThinking()) {
                        List<dev.langchain4j.agent.tool.ToolExecutionRequest> tools = aiMessage.toolExecutionRequests();
                        aiMessage = new AiMessage(thinking.content(), tools == null ? List.of() : tools);
                    }
                }
            }

            if (finalThinking != null && !finalThinking.isBlank()) {
                AgentContext.setThinkingContent(finalThinking);
            }
            if (progressSnapshot != null) {
                AgentContext.setStreamingProgress(progressSnapshot);
            }

            TokenUsage tokenUsage = OpenAiUtils.tokenUsageFrom(completion.usage());
            FinishReason finishReason = OpenAiCompatibleChatModelSupport.extractFinishReason(completion);

            if (shouldCapture && observabilityService != null) {
                reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, null,
                        finalThinking, progressSnapshot);
            }

            return ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .metadata(ChatResponseMetadata.builder()
                            .tokenUsage(tokenUsage)
                            .finishReason(finishReason)
                            .build())
                    .build();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (shouldCapture && observabilityService != null) {
                long durationMs = System.currentTimeMillis() - requestStartedAt;
                reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, "INTERRUPTED",
                        null, null);
            }
            String detail = "DashScope chat completion interrupted"
                    + " (model=" + OpenAiCompatibleChatModelSupport.nvl(modelName) + ")";
            throw new IllegalStateException(detail, e);

        } catch (Exception e) {
            if (shouldCapture && observabilityService != null) {
                long durationMs = System.currentTimeMillis() - requestStartedAt;
                String errorType = e.getClass().getSimpleName();
                reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs,
                        errorType + ": " + e.getMessage(), null, null);
            }
            String detail = "DashScope chat completion failed"
                    + " (model=" + OpenAiCompatibleChatModelSupport.nvl(modelName)
                    + ", error=" + OpenAiCompatibleChatModelSupport.shorten(e.getMessage())
                    + ", request=" + OpenAiCompatibleChatModelSupport.shorten(requestJson) + ")";
            log.warn(detail, e);
            throw new IllegalStateException(detail, e);
        }
    }

    private String extractReasoningContentFromResponse(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> json = objectMapper.readValue(responseJson, new TypeReference<>() {
            });
            Object choices = json.get("choices");
            if (choices instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> choice) {
                    Object message = choice.get("message");
                    if (message instanceof Map<?, ?> msg) {
                        Object rc = msg.get("reasoning_content");
                        if (rc instanceof String s && !s.isBlank()) {
                            return s;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从非流式响应提取 reasoning_content 失败: {}", e.getMessage());
        }
        return null;
    }

    private void reportLlmCall(
            RawHttpLogger.HttpRequestRecord request,
            RawHttpLogger.HttpResponseRecord response,
            String curlCommand,
            long startedAtMillis,
            long durationMs,
            String errorMessage,
            String thinkingContent,
            StreamingProgressTracker.StreamingProgressSnapshot streamingProgress) {

        if (observabilityService == null) {
            return;
        }

        String runId = AgentContext.getRunId();
        String phase = AgentContext.getPhase();

        if (runId == null || runId.isBlank()) {
            return;
        }

        TokenUsage tokenUsage = OpenAiCompatibleChatModelSupport.extractTokenUsageFromResponse(objectMapper, response, log);
        Integer cachedTokens = OpenAiCompatibleChatModelSupport.extractCachedTokensFromResponse(objectMapper, response, log);
        long completedAtMillis = startedAtMillis + durationMs;

        observabilityService.recordLlmCallWithRawHttp(
                runId,
                phase != null ? phase : "unknown",
                tokenUsage,
                cachedTokens,
                durationMs,
                startedAtMillis,
                completedAtMillis,
                endpointName,
                modelName,
                errorMessage,
                thinkingContent,
                streamingProgress,
                request,
                response,
                curlCommand
        );
    }

    private void applyThinkingConfig(Map<String, Object> requestJsonMap, List<ChatMessage> messages) {
        if (!enableThinking) {
            return;
        }
        if (!supportsThinking(modelName)) {
            return;
        }
        requestJsonMap.put("enable_thinking", true);
        requestJsonMap.put("thinking_budget", DEFAULT_THINKING_BUDGET);
    }

    private boolean supportsThinking(String modelName) {
        if (modelName == null) {
            return false;
        }
        String normalized = modelName.trim().toLowerCase();
        return normalized.startsWith("qwen3.5") || normalized.startsWith("qwen3.6");
    }

    private ThinkingContent extractThinkingContent(String content) {
        if (content == null || content.isBlank()) {
            return new ThinkingContent(content, "");
        }
        StringBuilder cleaned = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        int index = 0;
        while (true) {
            int start = content.indexOf("<think>", index);
            if (start < 0) {
                break;
            }
            int end = content.indexOf("</think>", start);
            if (end < 0) {
                break;
            }
            cleaned.append(content, index, start);
            String chunk = content.substring(start + "<think>".length(), end).trim();
            if (!chunk.isEmpty()) {
                if (thinking.length() > 0) {
                    thinking.append("\n");
                }
                thinking.append(chunk);
            }
            index = end + "</think>".length();
        }
        cleaned.append(content.substring(index));
        String cleanedText = cleaned.toString().trim();
        return new ThinkingContent(cleanedText, thinking.toString().trim());
    }

    private record ThinkingContent(String content, String thinking) {
        private boolean hasThinking() {
            return thinking != null && !thinking.isBlank();
        }
    }

    /**
     * 检查是否开启 curl debug 日志（热加载）。
     */
    private boolean isDebugCurlEnabled() {
        if (localConfigLoader == null) {
            return false;
        }
        return localConfigLoader.current()
                .map(cfg -> cfg.getDebug())
                .map(debug -> debug.getLogLlmCurl())
                .orElse(false);
    }

    /**
     * 构建 curl 命令字符串。
     */
    private String buildCurlCommand(String url, Map<String, String> headers, String body) {
        StringBuilder curl = new StringBuilder();
        curl.append("curl -X POST \\\n");
        curl.append("  \"").append(url).append("\" \\\n");
        if (headers != null) {
            headers.forEach((key, value) -> {
                String headerName = key.toLowerCase();
                if (headerName.contains("authorization")) {
                    curl.append("  -H \"").append(key).append(": Bearer $API_KEY\" \\\n");
                } else {
                    curl.append("  -H \"").append(key).append(": ").append(value).append("\" \\\n");
                }
            });
        }
        if (body != null && !body.isEmpty()) {
            String escapedBody = body.replace("'", "'\"'\"'");
            curl.append("  -d '").append(escapedBody).append("'");
        }
        return curl.toString();
    }
}

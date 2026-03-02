package world.willfrog.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.InternalOpenAiHelper;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import world.willfrog.agent.context.AgentContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope OpenAI 兼容 ChatModel 实现 (ALP-28)
 *
 * <p>与 OpenRouterProviderRoutedChatModel 分离，避免 provider 路由冲突。</p>
 */
@RequiredArgsConstructor
@Slf4j
public class DashScopeChatModel implements ChatLanguageModel {

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

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, List.of());
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
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
                    .messages(InternalOpenAiHelper.toOpenAiMessages(messages == null ? List.of() : messages))
                    .temperature(temperature)
                    .maxCompletionTokens(maxTokens);

            if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
                builder.tools(InternalOpenAiHelper.toTools(toolSpecifications, false));
            }

            ChatCompletionRequest request = builder.build();
            Map<String, Object> requestJsonMap = objectMapper.convertValue(
                    request,
                    new TypeReference<Map<String, Object>>() {
                    }
            );
            AgentContext.StructuredOutputSpec structuredOutputSpec = AgentContext.getStructuredOutputSpec();
            if (structuredOutputSpec != null) {
                requestJsonMap.put("response_format", structuredOutputSpec.asResponseFormat());
            }
            if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
                requestJsonMap.put("stream", false);
            }
            applyThinkingConfig(requestJsonMap, messages);

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

            HttpResponse<String> httpResponse = HTTP_CLIENT.send(
                    httpRequestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int statusCode = httpResponse.statusCode();
            String responseJson = httpResponse.body();
            long durationMs = System.currentTimeMillis() - requestStartedAt;

            if (shouldCapture) {
                Map<String, String> responseHeaders = httpLogger.extractHeaders(httpResponse);
                responseRecord = httpLogger.recordResponse(statusCode, responseHeaders, responseJson, durationMs);
                curlCommand = httpLogger.toCurlCommand(requestRecord);
            }

            if (statusCode < 200 || statusCode >= 300) {
                if (shouldCapture && observabilityService != null) {
                    reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs,
                            "HTTP_ERROR_" + statusCode);
                }
                String detail = "DashScope chat completion failed"
                        + " (http=" + statusCode
                        + ", model=" + OpenAiCompatibleChatModelSupport.nvl(modelName)
                        + ", error=" + OpenAiCompatibleChatModelSupport.shorten(responseJson)
                        + ", request=" + OpenAiCompatibleChatModelSupport.shorten(requestJson) + ")";
                log.warn(detail);
                throw new IllegalStateException(detail);
            }

            ChatCompletionResponse completion = objectMapper.readValue(responseJson, ChatCompletionResponse.class);
            AiMessage aiMessage = InternalOpenAiHelper.aiMessageFrom(completion);
            ThinkingContent thinking = extractThinkingContent(aiMessage == null ? null : aiMessage.text());
            if (aiMessage != null && thinking.hasThinking()) {
                List<dev.langchain4j.agent.tool.ToolExecutionRequest> tools = aiMessage.toolExecutionRequests();
                aiMessage = new AiMessage(thinking.content(), tools == null ? List.of() : tools);
            }
            TokenUsage tokenUsage = InternalOpenAiHelper.tokenUsageFrom(completion.usage());
            FinishReason finishReason = OpenAiCompatibleChatModelSupport.extractFinishReason(completion);

            Map<String, Object> metadata = new LinkedHashMap<>();
            if (completion.id() != null) {
                metadata.put("id", completion.id());
            }
            if (completion.model() != null) {
                metadata.put("model", completion.model());
            }
            if (thinking.hasThinking()) {
                metadata.put("thinking", thinking.thinking());
            }

            if (shouldCapture && observabilityService != null) {
                reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, null);
            }

            return Response.from(aiMessage, tokenUsage, finishReason, metadata);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            if (shouldCapture && observabilityService != null) {
                long durationMs = System.currentTimeMillis() - requestStartedAt;
                reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, "INTERRUPTED");
            }

            String detail = "DashScope chat completion interrupted"
                    + " (model=" + OpenAiCompatibleChatModelSupport.nvl(modelName) + ")";
            throw new IllegalStateException(detail, e);

        } catch (Exception e) {
            if (shouldCapture && observabilityService != null) {
                long durationMs = System.currentTimeMillis() - requestStartedAt;
                String errorType = e.getClass().getSimpleName();
                reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs,
                        errorType + ": " + e.getMessage());
            }

            String detail = "DashScope chat completion failed"
                    + " (model=" + OpenAiCompatibleChatModelSupport.nvl(modelName)
                    + ", error=" + OpenAiCompatibleChatModelSupport.shorten(e.getMessage())
                    + ", request=" + OpenAiCompatibleChatModelSupport.shorten(requestJson) + ")";
            log.warn(detail, e);
            throw new IllegalStateException(detail, e);
        }
    }

    private void reportLlmCall(
            RawHttpLogger.HttpRequestRecord request,
            RawHttpLogger.HttpResponseRecord response,
            String curlCommand,
            long startedAtMillis,
            long durationMs,
            String errorMessage) {

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
                request,
                response,
                curlCommand
        );
    }

    private void applyThinkingConfig(Map<String, Object> requestJsonMap, List<ChatMessage> messages) {
        if (!supportsThinking(modelName)) {
            return;
        }
        boolean enableThinking = resolveThinkingFromLatestUserMessage(messages);
        requestJsonMap.put("enable_thinking", enableThinking);
        if (enableThinking) {
            requestJsonMap.put("thinking_budget", DEFAULT_THINKING_BUDGET);
        }
    }

    private boolean resolveThinkingFromLatestUserMessage(List<ChatMessage> messages) {
        UserMessage latestUserMessage = findLatestUserMessage(messages);
        if (latestUserMessage == null || latestUserMessage.singleText() == null) {
            return true;
        }
        String text = latestUserMessage.singleText();
        int noThinkIndex = text.lastIndexOf("/no_think");
        int thinkIndex = text.lastIndexOf("/think");
        if (noThinkIndex < 0 && thinkIndex < 0) {
            return true;
        }
        if (noThinkIndex < 0) {
            return true;
        }
        if (thinkIndex < 0) {
            return false;
        }
        return thinkIndex > noThinkIndex;
    }

    private UserMessage findLatestUserMessage(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null) {
                continue;
            }
            if (message instanceof UserMessage userMessage) {
                return userMessage;
            }
        }
        return null;
    }

    private boolean supportsThinking(String modelName) {
        if (modelName == null) {
            return false;
        }
        String normalized = modelName.trim().toLowerCase();
        return normalized.startsWith("qwen3") || normalized.startsWith("qwq");
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
}

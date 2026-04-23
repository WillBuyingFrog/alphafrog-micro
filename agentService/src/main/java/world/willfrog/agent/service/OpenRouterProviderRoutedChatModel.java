package world.willfrog.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
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
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenRouter Provider 路由的 ChatModel 实现 (ALP-25)
 * 
 * <p>本类是 Agent LLM 调用的核心组件，支持：</p>
 * <ol>
 *   <li><b>Provider 优先级路由</b>：通过 providerOrder 指定优先使用的 Provider</li>
 *   <li><b>原始 HTTP 捕获</b>：完整记录请求/响应信息</li>
 *   <li><b>可观测性上报</b>：将 HTTP 观测数据上报到 AgentObservabilityService</li>
 *   <li><b>默认流式输出</b>：对 LLM Provider 使用 stream=true，内部聚合 SSE 流</li>
 * </ol>
 * 
 * @see AgentAiServiceFactory
 * @see RawHttpLogger
 * @see AgentObservabilityService
 * @since ALP-25
 */
@RequiredArgsConstructor
@Slf4j
public class OpenRouterProviderRoutedChatModel implements ChatModel {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    // ========== 核心依赖 ==========

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final Map<String, String> customHeaders;
    private final String modelName;
    private final Double temperature;
    private final Integer maxTokens;
    private final List<String> providerOrder;
    
    // ALP-25 新增：HTTP 记录和观测
    private final RawHttpLogger httpLogger;
    private final AgentObservabilityService observabilityService;
    private final OpenRouterCostService openRouterCostService;
    private final String endpointName;
    
    // Debug 配置加载器（热加载）
    private final AgentLlmLocalConfigLoader localConfigLoader;

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        List<ChatMessage> messages = chatRequest.messages();
        List<ToolSpecification> toolSpecifications = chatRequest.toolSpecifications();
        String requestJson = null;
        long requestStartedAt = System.currentTimeMillis();
        
        // ALP-25：判断是否记录 HTTP（客户端参数 + 服务端白名单）
        boolean clientWantsCapture = observabilityService != null 
                && observabilityService.isCaptureLlmRequestsEnabled(AgentContext.getRunId());
        boolean endpointAllowed = httpLogger != null && httpLogger.shouldCapture(endpointName);
        boolean shouldCapture = clientWantsCapture && endpointAllowed;
        RawHttpLogger.HttpRequestRecord requestRecord = null;
        RawHttpLogger.HttpResponseRecord responseRecord = null;
        String curlCommand = null;
        int statusCode = -1;
        String responseJson = null;
        
        try {
            // ========== 1. 构建请求 ==========
            ChatCompletionRequest.Builder builder = ChatCompletionRequest.builder()
                    .model(OpenAiCompatibleChatModelSupport.nvl(modelName))
                    .messages(OpenAiUtils.toOpenAiMessages(messages == null ? List.of() : messages))
                    .maxCompletionTokens(maxTokens);
            
            if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
                builder.tools(OpenAiUtils.toTools(toolSpecifications, false));
            }
            
            ChatCompletionRequest request = builder.build();
            Map<String, Object> requestJsonMap = objectMapper.convertValue(
                    request,
                    new TypeReference<Map<String, Object>>() {}
            );
            // 默认启用流式输出
            requestJsonMap.put("stream", true);
            requestJsonMap.put("stream_options", Map.of("include_usage", true));

            // OpenRouter 特有：添加 providerOrder 与结构化输出参数
            AgentContext.StructuredOutputSpec structuredOutputSpec = AgentContext.getStructuredOutputSpec();
            if (isOpenRouterEndpoint(baseUrl)) {
                Map<String, Object> provider = new LinkedHashMap<>();
                provider.put("order", providerOrder == null ? List.of() : providerOrder);
                if (structuredOutputSpec != null) {
                    requestJsonMap.put("response_format", structuredOutputSpec.asResponseFormat());
                    provider.put("require_parameters", structuredOutputSpec.requireProviderParameters());
                    boolean allowFallbacks = structuredOutputSpec.allowProviderFallbacks() 
                            || (providerOrder != null && providerOrder.size() > 1);
                    provider.put("allow_fallbacks", allowFallbacks);
                }
                requestJsonMap.put("provider", provider);

                // 添加 OpenRouter reasoning (thinking) 配置
                String reasoningEffort = AgentContext.getReasoningEffort();
                if (reasoningEffort != null && !reasoningEffort.isBlank()) {
                    Map<String, Object> reasoning = new LinkedHashMap<>();
                    reasoning.put("effort", reasoningEffort);
                    requestJsonMap.put("reasoning", reasoning);
                }
            } else if (structuredOutputSpec != null) {
                requestJsonMap.put("response_format", structuredOutputSpec.asResponseFormat());
            }

            requestJson = objectMapper.writeValueAsString(requestJsonMap);
            if (log.isDebugEnabled()) {
                log.debug("OpenRouter provider routing enabled: providers={}, structuredSchema={}",
                        providerOrder,
                        structuredOutputSpec == null ? "" : structuredOutputSpec.schemaName());
            }
            
            // 构建 HTTP 请求信息
            String requestUrl = OpenAiCompatibleChatModelSupport.buildChatCompletionsUrl(baseUrl);
            Map<String, String> requestHeaders = OpenAiCompatibleChatModelSupport.buildRequestHeaders(apiKey);
            
            // 确保 requestHeaders 包含所有实际发送的 headers
            requestHeaders.put("Content-Type", "application/json");
            requestHeaders.put("Accept", "application/json");
            
            HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + OpenAiCompatibleChatModelSupport.nvl(apiKey))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));
            
            // 添加自定义 headers
            if (customHeaders != null && !customHeaders.isEmpty()) {
                for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        httpRequestBuilder.header(entry.getKey(), entry.getValue());
                        requestHeaders.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            
            // ALP-25：记录 HTTP 请求
            if (shouldCapture) {
                requestRecord = httpLogger.recordRequest(requestUrl, "POST", requestHeaders, requestJson);
            }
            
            // Debug curl 日志（热加载配置）
            if (isDebugCurlEnabled()) {
                curlCommand = buildCurlCommand(requestUrl, requestHeaders, requestJson);
                log.info("[LLM Debug CURL] endpoint={} model={} providerOrder={}\n{}", 
                        endpointName, modelName, providerOrder, curlCommand);
            }
            
            // ========== 2. 发送 HTTP 请求（流式） ==========
            HttpResponse<java.io.InputStream> httpResponse = HTTP_CLIENT.send(
                    httpRequestBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            
            // ========== 3. 处理响应 ==========
            statusCode = httpResponse.statusCode();
            long durationMs = System.currentTimeMillis() - requestStartedAt;
            
            ChatCompletionResponse completion;
            String reasoningContent = null;
            StreamingProgressTracker.StreamingProgressSnapshot progressSnapshot = null;

            if (statusCode >= 200 && statusCode < 300) {
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
                // 错误响应：读取完整 body
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(httpResponse.body(), StandardCharsets.UTF_8))) {
                    responseJson = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                }
                
                if (shouldCapture) {
                    Map<String, String> responseHeaders = httpLogger.extractHeaders(httpResponse);
                    responseRecord = httpLogger.recordResponse(statusCode, responseHeaders, responseJson, durationMs);
                    curlCommand = httpLogger.toCurlCommand(requestRecord);
                }
                
                // 处理 HTTP 错误状态码
                if (shouldCapture && observabilityService != null) {
                    reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, 
                             "HTTP_ERROR_" + statusCode, null, null);
                }
                
                String detail = "OpenRouter provider routed chat completion failed"
                        + " (http=" + statusCode
                        + ", providers=" + providerOrder
                        + ", model=" + OpenAiCompatibleChatModelSupport.nvl(modelName)
                        + ", error=" + OpenAiCompatibleChatModelSupport.shorten(responseJson)
                        + ", request=" + OpenAiCompatibleChatModelSupport.shorten(requestJson) + ")";
                log.warn(detail);
                throw new IllegalStateException(detail);
            }
            
            // 解析响应体
            AiMessage aiMessage = OpenAiUtils.aiMessageFrom(completion);
            TokenUsage tokenUsage = OpenAiUtils.tokenUsageFrom(completion.usage());
            FinishReason finishReason = OpenAiCompatibleChatModelSupport.extractFinishReason(completion);

            // 保存 thinking 内容和进度
            if (reasoningContent != null && !reasoningContent.isBlank()) {
                AgentContext.setThinkingContent(reasoningContent);
            }
            if (progressSnapshot != null) {
                AgentContext.setStreamingProgress(progressSnapshot);
            }
            
            // ALP-25：上报成功观测
            if (shouldCapture && observabilityService != null) {
                String traceId = reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, null,
                        reasoningContent, progressSnapshot);
                String runId = AgentContext.getRunId();
                if (shouldEnrichOpenRouterCost(runId, traceId, completion.id())) {
                    openRouterCostService.enrichCostInfoAsync(runId, traceId, completion.id(), apiKey, baseUrl);
                }
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
            
            // ALP-25：上报中断错误
            if (shouldCapture && observabilityService != null) {
                long durationMs = System.currentTimeMillis() - requestStartedAt;
                reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, "INTERRUPTED",
                        null, null);
            }
            
            String detail = "OpenRouter provider routed chat completion interrupted"
                    + " (providers=" + providerOrder
                    + ", model=" + OpenAiCompatibleChatModelSupport.nvl(modelName) + ")";
            throw new IllegalStateException(detail, e);
            
        } catch (Exception e) {
            // ALP-25：上报异常
            if (shouldCapture && observabilityService != null) {
                long durationMs = System.currentTimeMillis() - requestStartedAt;
                String errorType = e.getClass().getSimpleName();
                reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, 
                            errorType + ": " + e.getMessage(), null, null);
            }
            
            String detail = "OpenRouter provider routed chat completion failed"
                    + " (providers=" + providerOrder
                    + ", model=" + OpenAiCompatibleChatModelSupport.nvl(modelName)
                    + ", error=" + OpenAiCompatibleChatModelSupport.shorten(e.getMessage())
                    + ", request=" + OpenAiCompatibleChatModelSupport.shorten(requestJson) + ")";
            log.warn(detail, e);
            throw new IllegalStateException(detail, e);
        }
    }
    
    /**
     * 上报 LLM 调用观测数据（ALP-25）。
     */
    private String reportLlmCall(
            RawHttpLogger.HttpRequestRecord request,
            RawHttpLogger.HttpResponseRecord response,
            String curlCommand,
            long startedAtMillis,
            long durationMs,
            String errorMessage,
            String thinkingContent,
            StreamingProgressTracker.StreamingProgressSnapshot streamingProgress) {
        
        if (observabilityService == null) {
            return null;
        }
        
        String runId = AgentContext.getRunId();
        String phase = AgentContext.getPhase();
        
        if (runId == null || runId.isBlank()) {
            return null;
        }
        
        TokenUsage tokenUsage = OpenAiCompatibleChatModelSupport.extractTokenUsageFromResponse(objectMapper, response, log);
        Integer cachedTokens = OpenAiCompatibleChatModelSupport.extractCachedTokensFromResponse(objectMapper, response, log);
        long completedAtMillis = startedAtMillis + durationMs;
        
        return observabilityService.recordLlmCallWithRawHttp(
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

    private boolean shouldEnrichOpenRouterCost(String runId, String traceId, String generationId) {
        return isOpenRouterEndpoint(baseUrl)
                && openRouterCostService != null
                && runId != null
                && !runId.isBlank()
                && traceId != null
                && generationId != null
                && !generationId.isBlank();
    }

    private boolean isOpenRouterEndpoint(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            return isOpenRouterHost(uri.getHost());
        } catch (IllegalArgumentException e) {
            try {
                URI uri = new URI(url.trim());
                return isOpenRouterHost(uri.getHost());
            } catch (URISyntaxException ignored) {
                return false;
            }
        }
    }

    private boolean isOpenRouterHost(String host) {
        return host != null && (host.equals("openrouter.ai") || host.endsWith(".openrouter.ai"));
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

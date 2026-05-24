package world.willfrog.agent.platform.service;

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
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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

    @Setter
    private AgentRunBudgetService budgetService;

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
        List<Map<String, Object>> attempts = List.of();
        
        try {
            if (budgetService != null) {
                budgetService.checkBeforeLlmCall();
            }
            // ========== 1. 构建请求 ==========
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
                    new TypeReference<Map<String, Object>>() {}
            );
            // 默认启用流式输出。SSE 聚合器负责还原 content/reasoning/tool_calls。
            requestJsonMap.put("stream", true);
            applyStreamingOptions(requestJsonMap, baseUrl, AgentContext.getPhase());
            applyEndpointSamplingDefaults(requestJsonMap, baseUrl);

            // OpenRouter 特有：添加 providerOrder 与结构化输出参数
            AgentContext.StructuredOutputSpec structuredOutputSpec = AgentContext.getStructuredOutputSpec();
            if (isOpenRouterEndpoint(baseUrl)) {
                normalizeOpenRouterTokenLimit(requestJsonMap);
                Map<String, Object> provider = new LinkedHashMap<>();
                provider.put("order", providerOrder == null ? List.of() : providerOrder);
                // 始终禁止 OpenRouter 自动 fallback 到其它 provider
                provider.put("allow_fallbacks", false);
                if (structuredOutputSpec != null) {
                    requestJsonMap.put("response_format", structuredOutputSpec.asResponseFormat());
                    provider.put("require_parameters", structuredOutputSpec.requireProviderParameters());
                }
                requestJsonMap.put("provider", provider);

                // 添加 OpenRouter reasoning (thinking) 配置
                String reasoningEffort = AgentContext.getReasoningEffort();
                if (reasoningEffort != null && !reasoningEffort.isBlank()) {
                    Map<String, Object> reasoning = new LinkedHashMap<>();
                    reasoning.put("effort", reasoningEffort);
                    requestJsonMap.put("reasoning", reasoning);
                }
            } else if (isFireworksEndpoint(baseUrl)) {
                String reasoningEffort = AgentContext.getReasoningEffort();
                applyFireworksReasoningEffort(requestJsonMap, reasoningEffort);
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
            
            Duration requestTimeout = resolveRequestTimeout();
            HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .timeout(requestTimeout)
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
            
            // ========== 2. 发送 HTTP 请求（流式，按 logical call 聚合重试） ==========
            int maxAttempts = budgetService == null ? 2 : budgetService.maxHttpAttemptsPerLogicalCall();
            AttemptResult attemptResult = sendWithRetry(httpRequestBuilder, shouldCapture, requestRecord, requestStartedAt,
                    maxAttempts, requestTimeout);
            attempts = attemptResult.attempts();
            HttpResponse<java.io.InputStream> httpResponse = attemptResult.response();
            responseRecord = attemptResult.responseRecord();
            statusCode = attemptResult.statusCode();
            responseJson = attemptResult.errorBody();
            long durationMs = System.currentTimeMillis() - requestStartedAt;
            
            ChatCompletionResponse completion;
            String reasoningContent = null;
            StreamingProgressTracker.StreamingProgressSnapshot progressSnapshot = null;

            if (statusCode >= 200 && statusCode < 300) {
                // 流式响应：解析 SSE
                StreamingProgressTracker tracker = createStreamingProgressTracker();
                OpenAiCompatibleChatModelSupport.SseAggregateResult aggregateResult =
                        OpenAiCompatibleChatModelSupport.aggregateSseStream(
                                httpResponse.body(), objectMapper, log, tracker
                        );
                durationMs = System.currentTimeMillis() - requestStartedAt;
                progressSnapshot = tracker.onStreamComplete(durationMs);
                completion = aggregateResult.completionResponse();
                reasoningContent = aggregateResult.reasoningContent();

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
            
            // 始终记录基本观测（llmCalls/token/duration），即使不开 raw HTTP capture
            if (observabilityService != null) {
                String runId = AgentContext.getRunId();
                if (runId != null && !runId.isBlank()) {
                    observabilityService.recordLlmCall(
                            runId,
                            AgentContext.getPhase() != null ? AgentContext.getPhase() : "unknown",
                            tokenUsage,
                            durationMs,
                            requestStartedAt,
                            requestStartedAt + durationMs,
                            endpointName,
                            modelName,
                            null,
                            null,
                            null
                    );
                }
            }

            // ALP-25：上报成功观测（含 raw HTTP）
            if (shouldCapture && observabilityService != null) {
                reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, null,
                        reasoningContent, progressSnapshot, attemptResult.attempts());
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
                        null, null, List.of());
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
                            errorType + ": " + e.getMessage(), null, null, attempts);
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
        return reportLlmCall(request, response, curlCommand, startedAtMillis, durationMs, errorMessage,
                thinkingContent, streamingProgress, List.of());
    }

    private String reportLlmCall(
            RawHttpLogger.HttpRequestRecord request,
            RawHttpLogger.HttpResponseRecord response,
            String curlCommand,
            long startedAtMillis,
            long durationMs,
            String errorMessage,
            String thinkingContent,
            StreamingProgressTracker.StreamingProgressSnapshot streamingProgress,
            List<Map<String, Object>> attempts) {
        
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
        
        String traceId = observabilityService.recordLlmCallWithRawHttp(
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
                curlCommand,
                attempts
        );
        AgentContext.setProviderLlmTraceId(traceId);
        return traceId;
    }

    private StreamingProgressTracker createStreamingProgressTracker() {
        String runId = AgentContext.getRunId();
        String phase = AgentContext.getPhase();
        boolean reportEnabled = isStreamingProgressReportEnabled()
                && observabilityService != null
                && runId != null
                && !runId.isBlank();
        return new StreamingProgressTracker(
                log,
                modelName,
                endpointName,
                isSseProgressLogEnabled(),
                reportEnabled,
                streamingProgressUpdateIntervalMs(),
                (snapshot, completed) -> observabilityService.recordStreamingProgress(
                        runId,
                        phase != null ? phase : "unknown",
                        endpointName,
                        modelName,
                        snapshot,
                        completed
                )
        );
    }

    private AttemptResult sendWithRetry(HttpRequest.Builder httpRequestBuilder,
                                        boolean shouldCapture,
                                        RawHttpLogger.HttpRequestRecord requestRecord,
                                        long logicalStartedAt,
                                        int maxAttempts,
                                        Duration requestTimeout) throws IOException, InterruptedException {
        List<Map<String, Object>> attempts = new ArrayList<>();
        Exception lastException = null;
        RawHttpLogger.HttpResponseRecord lastResponseRecord = null;
        String lastErrorBody = null;
        int lastStatusCode = -1;
        int cappedAttempts = Math.max(1, maxAttempts);
        for (int attempt = 1; attempt <= cappedAttempts; attempt++) {
            if (budgetService != null) {
                budgetService.checkHttpAttempt(attempt);
            }
            long attemptStarted = System.currentTimeMillis();
            try {
                HttpResponse<java.io.InputStream> httpResponse = HTTP_CLIENT.send(
                        httpRequestBuilder.build(),
                        HttpResponse.BodyHandlers.ofInputStream()
                );
                int status = httpResponse.statusCode();
                long attemptDuration = System.currentTimeMillis() - attemptStarted;
                Map<String, Object> attemptMeta = new LinkedHashMap<>();
                attemptMeta.put("attempt", attempt);
                attemptMeta.put("httpStatus", status);
                attemptMeta.put("durationMs", attemptDuration);
                attemptMeta.put("timeoutSeconds", requestTimeout.toSeconds());
                attempts.add(attemptMeta);

                if (status >= 200 && status < 300) {
                    return new AttemptResult(httpResponse, null, status, null, attempts);
                }

                String body;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(httpResponse.body(), StandardCharsets.UTF_8))) {
                    body = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                }
                lastErrorBody = body;
                lastStatusCode = status;
                if (shouldCapture && httpLogger != null) {
                    Map<String, String> responseHeaders = httpLogger.extractHeaders(httpResponse);
                    lastResponseRecord = httpLogger.recordResponse(
                            status, responseHeaders, body, System.currentTimeMillis() - logicalStartedAt);
                }
                attemptMeta.put("retryable", isRetryableStatus(status));
                attemptMeta.put("error", OpenAiCompatibleChatModelSupport.shorten(body));
                if (!isRetryableStatus(status) || attempt >= cappedAttempts) {
                    return new AttemptResult(httpResponse, lastResponseRecord, status, body, attempts);
                }
            } catch (IOException e) {
                long attemptDuration = System.currentTimeMillis() - attemptStarted;
                lastException = e;
                Map<String, Object> attemptMeta = new LinkedHashMap<>();
                attemptMeta.put("attempt", attempt);
                attemptMeta.put("durationMs", attemptDuration);
                attemptMeta.put("timeoutSeconds", requestTimeout.toSeconds());
                attemptMeta.put("retryable", true);
                attemptMeta.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                attempts.add(attemptMeta);
                if (attempt >= cappedAttempts) {
                    throw e;
                }
            }
            sleepBeforeRetry();
        }
        if (lastException instanceof IOException ioException) {
            throw ioException;
        }
        return new AttemptResult(null, lastResponseRecord, lastStatusCode, lastErrorBody, attempts);
    }

    private boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || (status >= 500 && status <= 599);
    }

    private void sleepBeforeRetry() throws InterruptedException {
        Thread.sleep(2000L);
    }

    private Duration resolveRequestTimeout() {
        return resolveRequestTimeout(AgentContext.getStage(), AgentContext.getPhase());
    }

    public static Duration resolveRequestTimeout(String stageValue, String phaseValue) {
        String stage = OpenAiCompatibleChatModelSupport.nvl(stageValue).toLowerCase();
        String phase = OpenAiCompatibleChatModelSupport.nvl(phaseValue).toLowerCase();
        if (phase.contains("planning") || stage.contains("planning") || stage.contains("final_answer")) {
            return Duration.ofSeconds(90);
        }
        if (stage.contains("semantic_judge")
                || stage.contains("search_evidence_judge")
                || stage.contains("tool_decision")
                || stage.endsWith("_decision")
                || stage.endsWith("_execute")
                || stage.endsWith("_plan")
                || phase.contains("decision")) {
            return Duration.ofSeconds(30);
        }
        return Duration.ofSeconds(60);
    }

    private record AttemptResult(
            HttpResponse<java.io.InputStream> response,
            RawHttpLogger.HttpResponseRecord responseRecord,
            int statusCode,
            String errorBody,
            List<Map<String, Object>> attempts
    ) {
    }

    private boolean isStreamingProgressReportEnabled() {
        return localConfigLoader == null
                || localConfigLoader.current()
                .map(AgentLlmProperties::getObservability)
                .map(AgentLlmProperties.Observability::getStreamingProgress)
                .map(AgentLlmProperties.StreamingProgress::getEnabled)
                .map(Boolean.TRUE::equals)
                .orElse(true);
    }

    private long streamingProgressUpdateIntervalMs() {
        return localConfigLoader == null ? 3000L
                : localConfigLoader.current()
                .map(AgentLlmProperties::getObservability)
                .map(AgentLlmProperties.Observability::getStreamingProgress)
                .map(AgentLlmProperties.StreamingProgress::getUpdateIntervalMs)
                .filter(v -> v != null && v > 0)
                .map(Integer::longValue)
                .orElse(3000L);
    }

    private boolean isSseProgressLogEnabled() {
        return localConfigLoader != null
                && localConfigLoader.current()
                .map(AgentLlmProperties::getDebug)
                .map(AgentLlmProperties.Debug::getLogSseProgress)
                .map(Boolean.TRUE::equals)
                .orElse(false);
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

    private boolean isFireworksEndpoint(String url) {
        return isFireworksEndpointUrl(url);
    }

    private boolean isOpenRouterHost(String host) {
        return host != null && (host.equals("openrouter.ai") || host.endsWith(".openrouter.ai"));
    }

    private static boolean isFireworksHost(String host) {
        return host != null && (host.equals("fireworks.ai") || host.endsWith(".fireworks.ai"));
    }

    public static void normalizeOpenRouterTokenLimit(Map<String, Object> requestJsonMap) {
        if (requestJsonMap == null) {
            return;
        }
        Object maxCompletionTokens = requestJsonMap.remove("max_completion_tokens");
        // OpenRouter 的 provider require_parameters 会按请求字段过滤供应商。
        // 对 Kimi/Fireworks 等 OpenAI 兼容模型，max_completion_tokens 会导致供应商被过滤；
        // 使用 OpenRouter Chat Completions 通用字段 max_tokens 更稳定。
        if (maxCompletionTokens != null && !requestJsonMap.containsKey("max_tokens")) {
            requestJsonMap.put("max_tokens", maxCompletionTokens);
        }
    }

    public static void applyStreamingOptions(Map<String, Object> requestJsonMap, String baseUrl) {
        applyStreamingOptions(requestJsonMap, baseUrl, AgentContext.getPhase());
    }

    /**
     * OpenRouter 流式选项。planning 阶段跳过 {@code stream_options}，避免在
     * {@code provider.require_parameters=true} 时因 provider 未声明支持该字段而被误过滤。
     */
    public static void applyStreamingOptions(Map<String, Object> requestJsonMap, String baseUrl, String phase) {
        if (requestJsonMap == null) {
            return;
        }
        if (isFireworksEndpointUrl(baseUrl)) {
            // Fireworks 当前 API 文档没有列出 stream_options；流式 perf metrics 通过最终 chunk 返回。
            requestJsonMap.remove("stream_options");
            requestJsonMap.put("perf_metrics_in_response", true);
            return;
        }
        if (AgentObservabilityService.PHASE_PLANNING.equals(phase)) {
            requestJsonMap.remove("stream_options");
            requestJsonMap.remove("perf_metrics_in_response");
            return;
        }
        requestJsonMap.put("stream_options", Map.of("include_usage", true));
        requestJsonMap.remove("perf_metrics_in_response");
    }

    public static void applyFireworksReasoningEffort(Map<String, Object> requestJsonMap, String reasoningEffort) {
        if (requestJsonMap == null || reasoningEffort == null || reasoningEffort.isBlank()) {
            return;
        }
        requestJsonMap.put("reasoning_effort", reasoningEffort);
    }

    public static void applyEndpointSamplingDefaults(Map<String, Object> requestJsonMap, String baseUrl) {
        if (requestJsonMap == null) {
            return;
        }
        if (isFireworksEndpointUrl(baseUrl)) {
            // Fireworks 实验使用服务端默认采样参数，避免本地全局默认 temperature 干扰。
            requestJsonMap.remove("temperature");
        }
    }

    private static boolean isFireworksEndpointUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            return isFireworksHost(uri.getHost());
        } catch (IllegalArgumentException e) {
            try {
                URI uri = new URI(url.trim());
                return isFireworksHost(uri.getHost());
            } catch (URISyntaxException ignored) {
                return false;
            }
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

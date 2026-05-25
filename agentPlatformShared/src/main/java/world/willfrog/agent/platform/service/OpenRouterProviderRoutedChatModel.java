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
 * OpenRouter Provider 路由的 ChatModel 实现 (ALP-25)。
 *
 * <p>这个类是 agentLangchainService 真正发起模型请求的位置。上一层
 * {@link AgentAiServiceFactory} 只负责按阶段构造 ChatModel，到了这里才会把
 * LangChain4j 的 {@link ChatRequest} 转成 OpenAI 兼容的 chat completions HTTP 请求。
 * 因此面试里被问到「模型请求里到底带了什么」「为什么 OpenRouter 会走某个 provider」
 * 「observability 里的 llm trace 从哪里来」时，答案都在这个文件。</p>
 *
 * <p>与普通 SDK 封装不同，本类刻意没有直接依赖某个现成 OpenAI client，而是手写
 * HTTP 请求和 SSE 聚合。原因是 agent 运行需要额外控制 provider order、结构化输出、
 * raw HTTP 捕获、streaming progress 和预算检查；这些都必须和 {@link AgentContext}
 * 中的 runId / phase / stage 绑定。</p>
 *
 * <p>本类支持：</p>
 * <ol>
 *   <li><b>Provider 优先级路由</b>：通过 providerOrder 指定优先使用的 Provider</li>
 *   <li><b>原始 HTTP 捕获</b>：完整记录请求/响应信息</li>
 *   <li><b>可观测性上报</b>：将 HTTP 观测数据上报到 AgentObservabilityService</li>
 *   <li><b>默认流式输出</b>：对 LLM Provider 使用 stream=true，内部聚合 SSE 流</li>
 * </ol>
 *
 * <p>需要特别注意 OpenRouter 的 fallback 语义：只要请求体没有显式写
 * {@code allow_fallbacks=false}，OpenRouter 可能在指定 provider 不可用时切到其它 provider。
 * 这会导致账单侧或第三方 provider 后台出现「明明配置了 A，实际走了 B」的现象。
 * 所以下方构造 provider 字段时始终显式禁止 fallback，把 provider 选择权留在配置层。</p>
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
        /*
         * doChat 是一次逻辑 LLM 调用的完整边界。这里的「一次」对应 observability
         * 里的 llmCallCount，而不是底层 HTTP attempt：sendWithRetry 可能在 5xx/429
         * 场景下对同一个逻辑调用重试多次，重试明细会写入 raw HTTP attempts。
         *
         * 处理顺序故意保持为：
         *   1. 预算检查；
         *   2. 从 ChatRequest 构造 provider-specific request body；
         *   3. 发送流式 HTTP 并聚合 SSE；
         *   4. 解析 token usage / finish reason；
         *   5. 写入轻量 llmCalls 统计与可选 raw HTTP trace。
         *
         * 这样做的好处是：即使 raw HTTP capture 没开，预算和 llmCalls 统计仍然完整；
         * raw body 只作为排障增强能力，不参与主流程正确性。
         */
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

            // OpenRouter 特有：添加 providerOrder 与结构化输出参数。
            //
            // StructuredOutputSpec 由 planning 阶段通过 AgentContext 注入，最终会落到
            // OpenRouter 的 response_format=json_schema。Provider 字段必须与它一起处理：
            // - order 限定 provider 优先级；
            // - allow_fallbacks=false 阻止 OpenRouter 静默切到列表外 provider；
            // - require_parameters=true 时，OpenRouter 会过滤不支持 response_format/tools
            //   等字段的 provider，因此字段名兼容性会直接影响是否 404。
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
            //
            // 这里的 retry 是 provider HTTP 层面的恢复，不等同于 agent 的语义重试：
            // agent 语义重试会重新让模型思考，而 HTTP retry 只是同一份请求体重发。
            // 因此 retry 次数由 run budget 单独限制，避免一个 LLM step 因 provider 抖动
            // 消耗过多 wall-clock。
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
                // 流式响应：解析 SSE。
                //
                // OpenRouter/Fireworks 的流式响应会把 content、reasoning、tool_calls、
                // usage 等信息拆在多个 chunk 中返回。这里用聚合器还原成一个
                // ChatCompletionResponse，后续 LangChain4j 才能像非流式响应一样读取
                // AiMessage 和 TokenUsage。
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
            
            // 始终记录基本观测（llmCalls/token/duration），即使不开 raw HTTP capture。
            //
            // 这是 run 级统计的基础数据源。raw HTTP capture 可能因为配置、隐私或存储成本关闭，
            // 但 llmCalls 和 token 预算不能因此缺失；否则 matrix 会看到模型实际在请求，
            // observability 里却一直是 llm=1 之类的假象。
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
     *
     * <p>这个重载保留给没有 retry attempts 的旧调用点。实际主路径会调用下面带
     * attempts 参数的版本，把每次 HTTP attempt 的状态码、耗时、错误摘要一起写入 trace。</p>
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
        
        /*
         * raw HTTP trace 只在 runId 存在时写入，因为它是 run 详情页的一部分。
         * 这里会再次从 response 中提取 tokenUsage/cachedTokens，而不是复用 doChat
         * 中的 TokenUsage，原因是异常路径可能没有成功构造 ChatCompletionResponse，
         * 但 responseRecord 中仍然可能包含 provider 返回的 usage 或错误体。
         */
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
        /*
         * StreamingProgressTracker 解决的是长输出阶段「服务端还在流式返回，但前端看起来没动」
         * 的问题。它会按配置间隔写入 chunk 数、字符数、phase 等轻量进度，使 matrix poll
         * 能区分卡死、慢 summarizing、正常 tool execution 三种状态。
         */
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
        /*
         * 这里把多个 HTTP attempt 包装成一个 AttemptResult 返回给 doChat。
         * 调用方只看到一次 ChatResponse；observability 才会看到每次 attempt 的细节。
         * 这样可以避免 provider 轻微抖动直接暴露给 agent loop，同时保留排障证据。
         */
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
        /*
         * 超时时间按阶段分层：
         * - planning / final answer 需要较长时间，因为输出结构化计划或最终答案；
         * - judge / decision / execute 等小模型决策应快速返回；
         * - 其它阶段用中间值。
         *
         * 这里不是 run wall-clock budget。单次 HTTP timeout 只约束一次模型请求，
         * run budget 仍由 AgentRunBudgetService 统一判断。
         */
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
     *
     * <p>这个方法看起来像 provider 兼容细节，但实际会影响工具调用是否能启动：
     * OpenRouter 先按请求参数筛 provider，再转发请求。如果某个可用 provider 没声明
     * {@code stream_options}，即使它支持模型本身，也可能被过滤成 404。</p>
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

package world.willfrog.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
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
 * OpenRouter Provider 路由的 ChatModel 实现 (ALP-25)
 * 
 * <p>本类是 Agent LLM 调用的核心组件，支持：</p>
 * <ol>
 *   <li><b>Provider 优先级路由</b>：通过 providerOrder 指定优先使用的 Provider（如优先使用 moonshotai/int4）</li>
 *   <li><b>原始 HTTP 捕获</b>：完整记录请求/响应信息，用于问题诊断和 curl 复现</li>
 *   <li><b>可观测性上报</b>：将 HTTP 观测数据上报到 AgentObservabilityService</li>
 * </ol>
 * 
 * <p><b>使用场景：</b></p>
 * <p>当用户配置中指定了 providerOrder（如优先使用 Fireworks 提供的 Kimi K2.5）时，
 * AgentAiServiceFactory 会创建此类的实例，而非标准的 OpenAiChatModel。</p>
 * 
 * <p><b>HTTP 捕获流程（ALP-25）：</b></p>
 * <pre>
 * 1. 检查客户端是否要求捕获（captureLlmRequests=true）
 * 2. 检查 endpoint 是否在服务端白名单内（httpLogger.shouldCapture）
 * 3. 只有两者都满足时才记录 HTTP：
 *    - 记录请求：httpLogger.recordRequest(url, method, headers, body)
 *    - 发送 HTTP 请求
 *    - 记录响应：httpLogger.recordResponse(statusCode, headers, body, durationMs)
 *    - 生成 curl 命令：httpLogger.toCurlCommand(requestRecord)
 *    - 上报观测：observabilityService.recordLlmCallWithRawHttp(...)
 * </pre>
 * <p><b>注意：</b>压测时请确保 captureLlmRequests=false，避免存储爆炸。</p>
 * 
 * <p><b>与标准 OpenAiChatModel 的区别：</b></p>
 * <ul>
 *   <li>支持 providerOrder 参数（OpenRouter 特有）</li>
 *   <li>直接控制 HTTP 层，可捕获原始请求/响应</li>
 *   <li>集成可观测性上报</li>
 * </ul>
 * 
 * @see AgentAiServiceFactory
 * @see RawHttpLogger
 * @see AgentObservabilityService
 * @since ALP-25
 */
@RequiredArgsConstructor
@Slf4j
public class OpenRouterProviderRoutedChatModel implements ChatLanguageModel {

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
    private final String endpointName;
    
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 生成 AI 回复（不带工具）。
     * 
     * <p>代理到 {@link #generate(List, List)}，tools 参数为空列表。</p>
     */
    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, List.of());
    }

    /**
     * 生成 AI 回复（带工具支持）。
     * 
     * <p>核心方法，处理完整的 LLM 调用流程：</p>
     * <ol>
     *   <li>构建 ChatCompletionRequest（包含 providerOrder）</li>
     *   <li>记录原始 HTTP 请求（如启用捕获）</li>
     *   <li>发送 HTTP 请求到 LLM Provider</li>
     *   <li>记录原始 HTTP 响应</li>
     *   <li>解析响应，上报观测数据</li>
     * </ol>
     * 
     * <p><b>HTTP 捕获决策（双重检查，ALP-25）：</b></p>
     * <ol>
     *   <li><b>客户端参数：</b>请求中 captureLlmRequests=true 时启用</li>
     *   <li><b>服务端白名单：</b>endpoint 在 httpLogger.shouldCapture 白名单内</li>
     * </ol>
     * <p>只有两个条件同时满足时才记录 HTTP。压测时请设置 captureLlmRequests=false。</p>
     * 
     * @param messages 对话消息列表
     * @param toolSpecifications 可用工具定义
     * @return AI 回复响应
     * @throws IllegalStateException 当 HTTP 请求失败或响应解析失败时抛出
     */
    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        String requestJson = null;
        long requestStartedAt = System.currentTimeMillis();
        
        // ALP-25：判断是否记录 HTTP（客户端参数 + 服务端白名单）
        // 只有当客户端显式要求 captureLlmRequests=true 且 endpoint 在白名单内时才捕获
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
                    .model(nvl(modelName))
                    .messages(InternalOpenAiHelper.toOpenAiMessages(messages == null ? List.of() : messages))
                    .temperature(temperature)
                    .maxCompletionTokens(maxTokens);
            
            if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
                builder.tools(InternalOpenAiHelper.toTools(toolSpecifications, false));
            }
            
            ChatCompletionRequest request = builder.build();
            Map<String, Object> requestJsonMap = objectMapper.convertValue(
                    request,
                    new TypeReference<Map<String, Object>>() {}
            );
            // OpenRouter 特有：添加 providerOrder 与结构化输出参数。
            AgentContext.StructuredOutputSpec structuredOutputSpec = AgentContext.getStructuredOutputSpec();
            Map<String, Object> provider = new LinkedHashMap<>();
            provider.put("order", providerOrder == null ? List.of() : providerOrder);
            if (structuredOutputSpec != null) {
                requestJsonMap.put("response_format", structuredOutputSpec.asResponseFormat());
                provider.put("require_parameters", structuredOutputSpec.requireProviderParameters());
                provider.put("allow_fallbacks", structuredOutputSpec.allowProviderFallbacks());
            }
            requestJsonMap.put("provider", provider);

            requestJson = objectMapper.writeValueAsString(requestJsonMap);
            if (log.isDebugEnabled()) {
                log.debug("OpenRouter provider routing enabled: providers={}, structuredSchema={}",
                        providerOrder,
                        structuredOutputSpec == null ? "" : structuredOutputSpec.schemaName());
            }
            
            // 构建 HTTP 请求信息
            String requestUrl = buildChatCompletionsUrl();
            Map<String, String> requestHeaders = buildRequestHeaders();
            
            HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + nvl(apiKey))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));
            
            // 添加自定义 headers（如 OpenRouter 的 HTTP-Referer、X-Title）
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
            
            // ========== 2. 发送 HTTP 请求 ==========
            HttpResponse<String> httpResponse = httpClient.send(
                    httpRequestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            
            // ========== 3. 处理响应 ==========
            statusCode = httpResponse.statusCode();
            responseJson = httpResponse.body();
            long durationMs = System.currentTimeMillis() - requestStartedAt;
            
            // ALP-25：记录 HTTP 响应
            if (shouldCapture) {
                Map<String, String> responseHeaders = httpLogger.extractHeaders(httpResponse);
                responseRecord = httpLogger.recordResponse(statusCode, responseHeaders, responseJson, durationMs);
                curlCommand = httpLogger.toCurlCommand(requestRecord);
            }
            
            // 处理 HTTP 错误状态码
            if (statusCode < 200 || statusCode >= 300) {
                // ALP-25：上报错误观测
                if (shouldCapture && observabilityService != null) {
                    reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, 
                                 "HTTP_ERROR_" + statusCode);
                }
                
                String detail = "OpenRouter provider routed chat completion failed"
                        + " (http=" + statusCode
                        + ", providers=" + providerOrder
                        + ", model=" + nvl(modelName)
                        + ", error=" + shorten(responseJson)
                        + ", request=" + shorten(requestJson) + ")";
                log.warn(detail);
                throw new IllegalStateException(detail);
            }
            
            // 解析响应体
            ChatCompletionResponse completion = objectMapper.readValue(responseJson, ChatCompletionResponse.class);

            AiMessage aiMessage = InternalOpenAiHelper.aiMessageFrom(completion);
            TokenUsage tokenUsage = InternalOpenAiHelper.tokenUsageFrom(completion.usage());
            FinishReason finishReason = extractFinishReason(completion);
            
            // 构建 metadata
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (completion.id() != null) {
                metadata.put("id", completion.id());
            }
            if (completion.model() != null) {
                metadata.put("model", completion.model());
            }
            
            // ALP-25：上报成功观测
            if (shouldCapture && observabilityService != null) {
                reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, null);
            }
            
            return Response.from(aiMessage, tokenUsage, finishReason, metadata);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            
            // ALP-25：上报中断错误
            if (shouldCapture && observabilityService != null) {
                long durationMs = System.currentTimeMillis() - requestStartedAt;
                reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, "INTERRUPTED");
            }
            
            String detail = "OpenRouter provider routed chat completion interrupted"
                    + " (providers=" + providerOrder
                    + ", model=" + nvl(modelName) + ")";
            throw new IllegalStateException(detail, e);
            
        } catch (Exception e) {
            // ALP-25：上报异常
            if (shouldCapture && observabilityService != null) {
                long durationMs = System.currentTimeMillis() - requestStartedAt;
                String errorType = e.getClass().getSimpleName();
                reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, 
                            errorType + ": " + e.getMessage());
            }
            
            String detail = "OpenRouter provider routed chat completion failed"
                    + " (providers=" + providerOrder
                    + ", model=" + nvl(modelName)
                    + ", error=" + shorten(e.getMessage())
                    + ", request=" + shorten(requestJson) + ")";
            log.warn(detail, e);
            throw new IllegalStateException(detail, e);
        }
    }
    
    /**
     * 上报 LLM 调用观测数据（ALP-25）。
     * 
     * <p>将 HTTP 请求/响应信息上报到 AgentObservabilityService，用于：</p>
     * <ul>
     *   <li>生成 curl 命令复现请求</li>
     *   <li>分析 Provider 差异</li>
     *   <li>故障诊断</li>
     * </ul>
     * 
     * <p><b>注意：</b>只有在当前线程有 AgentContext（runId 不为空）时才会上报。</p>
     * 
     * @param request 请求记录
     * @param response 响应记录
     * @param curlCommand curl 命令
     * @param durationMs 请求耗时
     * @param errorMessage 错误信息（null 表示成功）
     */
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
        
        // 从 ThreadLocal 获取当前 run 信息
        String runId = AgentContext.getRunId();
        String phase = AgentContext.getPhase();
        
        if (runId == null || runId.isBlank()) {
            // 不在 Agent 执行上下文中，不上报（避免污染其他线程的数据）
            return;
        }
        
        // 从响应中提取 token usage
        TokenUsage tokenUsage = extractTokenUsageFromResponse(response);
        long completedAtMillis = startedAtMillis + durationMs;
        
        observabilityService.recordLlmCallWithRawHttp(
                runId,
                phase != null ? phase : "unknown",
                tokenUsage,
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
    
    /**
     * 从响应记录中提取 TokenUsage。
     * 
     * <p>解析响应 JSON 中的 usage 字段，提取 prompt_tokens、completion_tokens、total_tokens。</p>
     * <p>如解析失败，返回 null（观测数据中会显示为 null，不影响功能）。</p>
     * 
     * @param response HTTP 响应记录
     * @return TokenUsage 对象，或 null（解析失败时）
     */
    private TokenUsage extractTokenUsageFromResponse(RawHttpLogger.HttpResponseRecord response) {
        if (response == null || response.getBody() == null || response.getBody().isBlank()) {
            return null;
        }
        
        try {
            Map<String, Object> json = objectMapper.readValue(response.getBody(), new TypeReference<>() {});
            Object usage = json.get("usage");
            if (usage instanceof Map<?, ?> usageMap) {
                Integer promptTokens = toInt(usageMap.get("prompt_tokens"));
                Integer completionTokens = toInt(usageMap.get("completion_tokens"));
                Integer totalTokens = toInt(usageMap.get("total_tokens"));
                
                // 如果 totalTokens 为空，自动计算
                int total = totalTokens != null ? totalTokens : 
                    ((promptTokens != null ? promptTokens : 0) + (completionTokens != null ? completionTokens : 0));
                
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
     * 安全转换为 Integer。
     * 
     * @param value 任意类型的值
     * @return Integer，或 null（转换失败时）
     */
    private Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 构建请求 headers Map。
     * 
     * <p>用于 HTTP 请求记录。</p>
     * 
     * @return headers 的 Map 表示
     */
    private Map<String, String> buildRequestHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + nvl(apiKey));
        return headers;
    }

    /**
     * 提取 finish_reason。
     * 
     * <p>从 ChatCompletionResponse 中提取 finish_reason 并转换为 LangChain4j 的 FinishReason。</p>
     * 
     * @param completion ChatCompletionResponse
     * @return FinishReason，或 null（无 finish_reason 时）
     */
    private FinishReason extractFinishReason(ChatCompletionResponse completion) {
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

    /**
     * null 值处理。
     * 
     * @param value 原始值
     * @return 非 null 值（null 时返回空字符串）
     */
    private String nvl(String value) {
        return value == null ? "" : value;
    }

    /**
     * 构建完整的 chat completions URL。
     * 
     * <p>处理 baseUrl 的各种情况：</p>
     * <ul>
     *   <li>https://api.openrouter.ai/api/v1 → https://api.openrouter.ai/api/v1/chat/completions</li>
     *   <li>https://api.openrouter.ai/api/v1/ → https://api.openrouter.ai/api/v1/chat/completions</li>
     *   <li>https://api.openrouter.ai/ → https://api.openrouter.ai/v1/chat/completions</li>
     * </ul>
     * 
     * @return 完整的 chat completions URL
     */
    private String buildChatCompletionsUrl() {
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

    /**
     * 缩短字符串（用于日志）。
     * 
     * <p>将换行符替换为空格，截断到 600 字符。</p>
     * 
     * @param text 原始字符串
     * @return 缩短后的字符串
     */
    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ');
        if (normalized.length() <= 600) {
            return normalized;
        }
        return normalized.substring(0, 600) + "...";
    }
}

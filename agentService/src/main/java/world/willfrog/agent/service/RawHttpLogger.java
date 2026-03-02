package world.willfrog.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentObservabilityProperties;

import java.net.http.HttpResponse;
import java.util.*;

/**
 * 原始 HTTP 请求/响应记录器 (ALP-25)
 * 
 * <p>负责捕获 LLM HTTP 请求的完整信息，支持：</p>
 * <ul>
 *   <li>完整请求/响应捕获（URL、headers、body）</li>
 *   <li>敏感信息自动脱敏（Authorization、apiKey）</li>
 *   <li>生成可直接执行的 curl 命令</li>
 *   <li>按 endpoint 选择性拦截</li>
 * </ul>
 * 
 * <p><b>拦截范围：</b></p>
 * <ul>
 *   <li>✅ LLM 请求（通过 OpenRouterProviderRoutedChatModel 显式调用）</li>
 *   <li>❌ 工具调用（Dubbo RPC，不走 HTTP）</li>
 *   <li>❌ 外部搜索工具（使用不同 HTTP client）</li>
 * </ul>
 * 
 * <p><b>配置示例：</b></p>
 * <pre>
 * agent:
 *   observability:
 *     raw-http:
 *       enabled: true
 *       capture-endpoints: [fireworks, openrouter]  # 只捕获这些 endpoint
 * </pre>
 * 
 * @see AgentObservabilityService
 * @see OpenRouterProviderRoutedChatModel
 * @since ALP-25
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RawHttpLogger {

    private final AgentObservabilityProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 判断是否记录特定 endpoint 的 HTTP 请求。
     * 
     * <p>判断逻辑（按优先级）：</p>
     * <ol>
     *   <li>全局开关检查</li>
     *   <li>排除名单检查（优先级最高）</li>
     *   <li>白名单检查（空表示全部捕获）</li>
     * </ol>
     * 
     * @param endpointName endpoint 名称（如 "fireworks", "openrouter"）
     * @return true 表示需要捕获，false 表示跳过
     */
    public boolean shouldCapture(String endpointName) {
        AgentObservabilityProperties.RawHttp config = properties.getRawHttp();
        
        // 1. 全局开关检查
        if (!config.isEnabled()) {
            return false;
        }
        
        String normalizedEndpoint = normalize(endpointName);
        if (normalizedEndpoint == null) {
            return false;
        }
        
        // 2. 排除名单检查（优先级最高）
        Set<String> excludeEndpoints = config.getExcludeEndpoints();
        if (excludeEndpoints != null && !excludeEndpoints.isEmpty()) {
            for (String exclude : excludeEndpoints) {
                if (normalizedEndpoint.equalsIgnoreCase(normalize(exclude))) {
                    return false;
                }
            }
        }
        
        // 3. 白名单检查（空表示全部捕获）
        Set<String> captureEndpoints = config.getCaptureEndpoints();
        if (captureEndpoints == null || captureEndpoints.isEmpty()) {
            return true;
        }
        
        for (String allowed : captureEndpoints) {
            if (normalizedEndpoint.equalsIgnoreCase(normalize(allowed))) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 记录 HTTP 请求信息。
     * 
     * <p>会对敏感信息进行脱敏处理：</p>
     * <ul>
     *   <li>Headers：Authorization 等敏感 header 会被脱敏</li>
     *   <li>Body：apiKey、token 等字段会被脱敏</li>
     * </ul>
     * 
     * @param url 请求 URL（完整地址，包含 path）
     * @param method HTTP 方法（如 "POST"）
     * @param headers 请求 headers（会被脱敏）
     * @param body 请求 body（JSON 字符串，会被脱敏和截断）
     * @return 请求记录对象，包含脱敏后的信息
     */
    public HttpRequestRecord recordRequest(String url, String method, 
                                           Map<String, String> headers, String body) {
        AgentObservabilityProperties.RawHttp config = properties.getRawHttp();
        
        // 根据配置决定是否捕获 headers
        Map<String, String> headersToRecord = config.isCaptureHeaders() 
            ? sanitizeHeaders(headers, config.getSensitiveHeaders()) 
            : Map.of();
        
        // 对 body 进行脱敏和截断
        String bodyToRecord = config.isSanitizeRequestBody() 
            ? sanitizeRequestBody(body) 
            : truncate(body, config.getCaptureBodyMaxChars());
        
        return HttpRequestRecord.builder()
                .url(url)
                .method(method)
                .headers(headersToRecord)
                .body(bodyToRecord)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 记录 HTTP 响应信息。
     * 
     * @param statusCode HTTP 状态码（如 200、429、500）
     * @param headers 响应 headers
     * @param body 响应 body（JSON 字符串）
     * @param durationMs 请求耗时（毫秒）
     * @return 响应记录对象
     */
    public HttpResponseRecord recordResponse(int statusCode, Map<String, String> headers,
                                             String body, long durationMs) {
        AgentObservabilityProperties.RawHttp config = properties.getRawHttp();
        
        Map<String, String> headersToRecord = config.isCaptureHeaders() 
            ? sanitizeHeaders(headers, config.getSensitiveHeaders()) 
            : Map.of();
        
        return HttpResponseRecord.builder()
                .statusCode(statusCode)
                .headers(headersToRecord)
                .body(truncate(body, config.getCaptureBodyMaxChars()))
                .durationMs(durationMs)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    /**
     * 从 HttpResponse 中提取 headers。
     * 
     * <p>将 HttpResponse 的 headers 转换为 Map 格式，方便后续处理。</p>
     * 
     * @param response HTTP 响应对象
     * @return headers 的 Map 表示
     */
    public Map<String, String> extractHeaders(HttpResponse<?> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((key, values) -> {
            if (!values.isEmpty()) {
                headers.put(key, String.join(", ", values));
            }
        });
        return headers;
    }

    /**
     * 生成可执行的 curl 命令。
     * 
     * <p>生成的 curl 命令特点：</p>
     * <ul>
     *   <li>可直接复制执行</li>
     *   <li>Authorization 使用环境变量 $API_KEY（已脱敏）</li>
     *   <li>body 中的单引号已正确转义</li>
     * </ul>
     * 
     * <p><b>使用示例：</b></p>
     * <pre>
     * export API_KEY=sk-your-actual-key
     * curl -X POST \
     *   "https://api.fireworks.ai/inference/v1/chat/completions" \
     *   -H "Authorization: Bearer $API_KEY" \
     *   -d '{...}'
     * </pre>
     * 
     * @param request 请求记录对象
     * @return curl 命令字符串，如未启用生成则返回空字符串
     */
    public String toCurlCommand(HttpRequestRecord request) {
        if (request == null) {
            return "";
        }
        
        AgentObservabilityProperties.RawHttp config = properties.getRawHttp();
        if (!config.isGenerateCurl()) {
            return "";
        }
        
        StringBuilder curl = new StringBuilder();
        curl.append("curl -X ").append(request.getMethod()).append(" \\\n");
        curl.append("  \"").append(request.getUrl()).append("\" \\\n");
        
        // 添加 headers
        if (request.getHeaders() != null) {
            request.getHeaders().forEach((key, value) -> {
                String headerName = key.toLowerCase();
                
                // 敏感 header 使用环境变量提示
                if (isSensitiveHeader(headerName, config.getSensitiveHeaders())) {
                    curl.append("  -H \"").append(key).append(": Bearer $API_KEY\" \\\n");
                } else {
                    curl.append("  -H \"").append(key).append(": ").append(value).append("\" \\\n");
                }
            });
        }
        
        // 添加 body（转义单引号）
        if (request.getBody() != null && !request.getBody().isEmpty()) {
            String escapedBody = request.getBody().replace("'", "'\"'\"'");
            curl.append("  -d '").append(escapedBody).append("'");
        }
        
        return curl.toString();
    }

    /**
     * 脱敏 headers。
     * 
     * <p>将敏感 header 的值脱敏为 mask 格式，例如：</p>
     * <pre>
     * Authorization: Bearer sk-abc123...xyz789 → Bearer sk-abc1...z789
     * </pre>
     * 
     * @param headers 原始 headers
     * @param sensitiveHeaders 敏感 header 名称集合
     * @return 脱敏后的 headers
     */
    private Map<String, String> sanitizeHeaders(Map<String, String> headers, Set<String> sensitiveHeaders) {
        if (headers == null) {
            return Map.of();
        }
        
        Map<String, String> sanitized = new LinkedHashMap<>();
        headers.forEach((key, value) -> {
            String headerName = key.toLowerCase();
            if (isSensitiveHeader(headerName, sensitiveHeaders)) {
                sanitized.put(key, maskToken(value));
            } else {
                sanitized.put(key, value);
            }
        });
        return sanitized;
    }
    
    /**
     * 脱敏请求 body 中的敏感字段。
     * 
     * <p>递归扫描 JSON body，对以下字段脱敏：</p>
     * <ul>
     *   <li>apiKey</li>
     *   <li>api_key</li>
     *   <li>token</li>
     * </ul>
     * 
     * @param body JSON 字符串
     * @return 脱敏后的 JSON 字符串
     */
    private String sanitizeRequestBody(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        
        try {
            Map<String, Object> jsonMap = objectMapper.readValue(body, new TypeReference<>() {});
            
            // 递归脱敏敏感字段
            sanitizeSensitiveFields(jsonMap);
            
            return truncate(objectMapper.writeValueAsString(jsonMap), 
                          properties.getRawHttp().getCaptureBodyMaxChars());
        } catch (Exception e) {
            // 解析失败，直接截断返回
            return truncate(body, properties.getRawHttp().getCaptureBodyMaxChars());
        }
    }
    
    /**
     * 递归脱敏敏感字段。
     * 
     * <p>深度遍历 JSON 对象，对所有敏感字段进行脱敏。</p>
     * 
     * @param map 待处理的 Map 对象（会被修改）
     */
    @SuppressWarnings("unchecked")
    private void sanitizeSensitiveFields(Map<String, Object> map) {
        map.forEach((key, value) -> {
            if (value instanceof String str) {
                // 脱敏 apiKey 等敏感字段
                if (key.toLowerCase().contains("apikey") || 
                    key.toLowerCase().contains("api_key") ||
                    key.toLowerCase().contains("token")) {
                    map.put(key, maskToken(str));
                }
            } else if (value instanceof Map) {
                sanitizeSensitiveFields((Map<String, Object>) value);
            } else if (value instanceof List) {
                sanitizeSensitiveFieldsInList((List<Object>) value);
            }
        });
    }
    
    /**
     * 递归脱敏列表中的敏感字段。
     * 
     * @param list 待处理的列表
     */
    @SuppressWarnings("unchecked")
    private void sanitizeSensitiveFieldsInList(List<Object> list) {
        for (Object item : list) {
            if (item instanceof Map) {
                sanitizeSensitiveFields((Map<String, Object>) item);
            }
        }
    }
    
    /**
     * 判断是否为敏感 header。
     * 
     * @param headerName header 名称（小写）
     * @param sensitiveHeaders 敏感 header 名称集合
     * @return true 表示敏感
     */
    private boolean isSensitiveHeader(String headerName, Set<String> sensitiveHeaders) {
        return sensitiveHeaders.stream()
                .anyMatch(h -> headerName.equalsIgnoreCase(h) || headerName.contains(h.toLowerCase()));
    }
    
    /**
     * 脱敏 token。
     * 
     * <p>保留 token 的前 8 位和后 4 位，中间用 ... 替换。</p>
     * <p>短 token 完全隐藏。</p>
     * 
     * @param token 原始 token
     * @return 脱敏后的 token（如 sk-abc1...z789）
     */
    private String maskToken(String token) {
        if (token == null || token.length() <= 12) {
            return "***masked***";
        }
        return token.substring(0, 8) + "..." + token.substring(token.length() - 4);
    }
    
    /**
     * 截断字符串。
     * 
     * @param text 原始字符串
     * @param maxChars 最大字符数
     * @return 截断后的字符串（如超出限制，添加 "...[truncated]" 后缀）
     */
    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...[truncated]";
    }
    
    /**
     * 标准化字符串（trim + 转小写）。
     * 
     * @param value 原始值
     * @return 标准化后的值，空值返回 null
     */
    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase();
    }

    // ============ 数据记录类 ============
    
    /**
     * HTTP 请求记录。
     * 
     * <p>包含请求的完整信息（已脱敏），用于后续生成 curl 命令和观测上报。</p>
     */
    @Data
    @Builder
    public static class HttpRequestRecord {
        /** 请求 URL（完整地址） */
        private String url;
        /** HTTP 方法（如 POST） */
        private String method;
        /** 请求 headers（已脱敏） */
        private Map<String, String> headers;
        /** 请求 body（JSON 字符串，已脱敏和截断） */
        private String body;
        /** 记录时间戳（毫秒） */
        private long timestamp;
    }
    
    /**
     * HTTP 响应记录。
     * 
     * <p>包含响应的完整信息，用于观测上报和故障诊断。</p>
     */
    @Data
    @Builder
    public static class HttpResponseRecord {
        /** HTTP 状态码（如 200、429、500） */
        private int statusCode;
        /** 响应 headers */
        private Map<String, String> headers;
        /** 响应 body（JSON 字符串，已截断） */
        private String body;
        /** 请求耗时（毫秒） */
        private long durationMs;
        /** 记录时间戳（毫秒） */
        private long timestamp;
    }
}

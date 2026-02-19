package world.willfrog.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 可观测性配置属性
 * 
 * 用于配置原始 HTTP 请求/响应捕获、Provider 诊断等功能
 * 
 * @see world.willfrog.agent.service.RawHttpLogger
 * @see world.willfrog.agent.service.AgentObservabilityService
 */
@Data
@ConfigurationProperties(prefix = "agent.observability")
public class AgentObservabilityProperties {

    /**
     * 原始 HTTP 捕获配置
     */
    private RawHttp rawHttp = new RawHttp();
    
    /**
     * Provider 诊断配置
     */
    private ProviderDiagnostic providerDiagnostic = new ProviderDiagnostic();
    
    @Data
    public static class RawHttp {
        /**
         * 是否启用原始 HTTP 捕获
         */
        private boolean enabled = true;
        
        /**
         * 捕获的 endpoint 白名单（空表示全部捕获）
         * 例如: ["fireworks", "openrouter", "dashscope"]
         */
        private Set<String> captureEndpoints = new HashSet<>();
        
        /**
         * 排除的 endpoint 名单（优先级高于白名单）
         */
        private Set<String> excludeEndpoints = new HashSet<>();
        
        /**
         * 捕获 body 的最大字符数
         */
        private int captureBodyMaxChars = 100000;
        
        /**
         * 是否捕获请求/响应 headers
         */
        private boolean captureHeaders = true;
        
        /**
         * 是否生成 curl 命令
         */
        private boolean generateCurl = true;
        
        /**
         * 敏感 header 脱敏配置
         */
        private Set<String> sensitiveHeaders = new HashSet<>(Set.of(
            "authorization",
            "x-api-key",
            "api-key"
        ));
        
        /**
         * 是否脱敏请求 body 中的敏感字段（如 apiKey）
         */
        private boolean sanitizeRequestBody = true;
    }
    
    @Data
    public static class ProviderDiagnostic {
        /**
         * 是否启用 Provider 诊断
         */
        private boolean enabled = false;
        
        /**
         * 测试用的模型列表
         */
        private List<String> testModels = new ArrayList<>();
        
        /**
         * 测试用的 endpoint 列表
         */
        private List<String> testEndpoints = new ArrayList<>();
        
        /**
         * 是否自动在新版本部署时运行诊断
         */
        private boolean runOnStartup = false;
    }
}

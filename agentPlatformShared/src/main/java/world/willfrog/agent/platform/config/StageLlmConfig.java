package world.willfrog.agent.platform.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 单个阶段的 LLM 配置（客户端 Run 级）。
 * endpointName + modelName 同时存在时视为有效配置。
 */
@Data
public class StageLlmConfig {
    private String endpointName;      // 必需
    private String modelName;         // 必需
    private String reasoningEffort;   // 可选，如 "high"/"medium"/"low"/"none"
    private Double temperature;       // 可选
    private Integer maxTokens;        // 可选
    @JsonAlias({"provider_order", "providers"})
    private List<String> providerOrder; // 可选，OpenRouter provider 路由顺序

    @JsonSetter("providerOrder")
    public void setProviderOrder(Object raw) {
        this.providerOrder = parseProviderOrder(raw);
    }

    @JsonSetter("provider_order")
    public void setProviderOrderSnake(Object raw) {
        this.providerOrder = parseProviderOrder(raw);
    }

    @JsonSetter("providers")
    public void setProviders(Object raw) {
        this.providerOrder = parseProviderOrder(raw);
    }

    /**
     * 判断当前配置是否有效（endpoint 和 model 都已指定）
     */
    public boolean isValid() {
        return endpointName != null && !endpointName.isBlank()
                && modelName != null && !modelName.isBlank();
    }

    private List<String> parseProviderOrder(Object raw) {
        if (raw == null) {
            return null;
        }
        List<String> providers = new ArrayList<>();
        if (raw instanceof Collection<?> values) {
            for (Object value : values) {
                addProviderTokens(providers, value);
            }
        } else if (raw.getClass().isArray()) {
            Object[] values = (Object[]) raw;
            for (Object value : values) {
                addProviderTokens(providers, value);
            }
        } else {
            addProviderTokens(providers, raw);
        }
        return providers.isEmpty() ? null : providers;
    }

    private void addProviderTokens(List<String> providers, Object raw) {
        if (raw == null) {
            return;
        }
        String text = String.valueOf(raw).trim();
        if (text.isBlank()) {
            return;
        }
        for (String token : text.split(",")) {
            String provider = token == null ? "" : token.trim();
            if (!provider.isBlank() && !providers.contains(provider)) {
                providers.add(provider);
            }
        }
    }
}

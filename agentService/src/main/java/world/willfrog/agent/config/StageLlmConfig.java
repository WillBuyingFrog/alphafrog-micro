package world.willfrog.agent.config;

import lombok.Data;

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

    /**
     * 判断当前配置是否有效（endpoint 和 model 都已指定）
     */
    public boolean isValid() {
        return endpointName != null && !endpointName.isBlank()
                && modelName != null && !modelName.isBlank();
    }
}

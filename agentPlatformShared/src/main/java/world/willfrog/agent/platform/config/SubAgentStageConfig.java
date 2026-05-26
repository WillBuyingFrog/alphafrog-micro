package world.willfrog.agent.platform.config;

import lombok.Data;

/**
 * Sub Agent 阶段配置：按低/中/高复杂度分别配置 LLM。
 */
@Data
public class SubAgentStageConfig {
    private StageLlmConfig lowComplexity;     // 低复杂度任务
    private StageLlmConfig mediumComplexity;  // 中复杂度任务
    private StageLlmConfig highComplexity;    // 高复杂度任务
}

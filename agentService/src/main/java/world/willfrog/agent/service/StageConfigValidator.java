package world.willfrog.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.config.RunStageConfig;
import world.willfrog.agent.config.StageLlmConfig;
import world.willfrog.agent.config.SubAgentStageConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阶段级 LLM 配置校验器。
 * <p>校验各阶段的 endpoint/model 是否在 agent-llm.local.json 的 endpoints 下定义，
 * reasoning_effort 值是否合法。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StageConfigValidator {

    private static final Set<String> VALID_REASONING_EFFORTS =
            Set.of("xhigh", "high", "medium", "low", "minimal", "none");

    private final AgentLlmProperties llmProperties;
    private final AgentLlmLocalConfigLoader localConfigLoader;

    /**
     * 校验 RunStageConfig，若有非法配置则抛出 IllegalArgumentException。
     */
    public void validate(RunStageConfig config) {
        if (config == null) {
            return;
        }
        List<String> errors = new ArrayList<>();
        validateStage(config.getPlanning(), "planning", errors);
        validateStage(config.getExecution(), "execution", errors);
        validateStage(config.getFinalAnswer(), "final_answer", errors);
        if (config.getSubAgent() != null) {
            validateStage(config.getSubAgent().getLowComplexity(), "sub_agent.low_complexity", errors);
            validateStage(config.getSubAgent().getMediumComplexity(), "sub_agent.medium_complexity", errors);
            validateStage(config.getSubAgent().getHighComplexity(), "sub_agent.high_complexity", errors);
        }
        if (!errors.isEmpty()) {
            String msg = "阶段 LLM 配置校验失败: " + String.join("; ", errors);
            log.warn(msg);
            throw new IllegalArgumentException(msg);
        }
    }

    private void validateStage(StageLlmConfig stageCfg, String stageName, List<String> errors) {
        if (stageCfg == null || !stageCfg.isValid()) {
            return;
        }
        // 校验 endpoint 是否存在
        Map<String, AgentLlmProperties.Endpoint> endpoints = collectEndpoints();
        String epName = stageCfg.getEndpointName();
        if (!endpoints.containsKey(epName)) {
            errors.add(stageName + ": endpoint '" + epName + "' 未在配置中定义");
            return; // endpoint 不存在时跳过 model 校验
        }

        // 校验 model 是否在该 endpoint 下定义
        AgentLlmProperties.Endpoint ep = endpoints.get(epName);
        if (ep.getModels() != null && !ep.getModels().isEmpty()) {
            if (!ep.getModels().containsKey(stageCfg.getModelName())) {
                errors.add(stageName + ": model '" + stageCfg.getModelName()
                        + "' 未在 endpoint '" + epName + "' 下定义");
            }
        }

        // 校验 reasoning_effort
        if (stageCfg.getReasoningEffort() != null && !stageCfg.getReasoningEffort().isBlank()) {
            String effort = stageCfg.getReasoningEffort().trim().toLowerCase();
            if (!VALID_REASONING_EFFORTS.contains(effort)) {
                errors.add(stageName + ": reasoning_effort '" + stageCfg.getReasoningEffort()
                        + "' 不合法，允许值: " + VALID_REASONING_EFFORTS);
            }
        }
    }

    /**
     * 收集所有可用 endpoints（base + local 合并）。
     */
    private Map<String, AgentLlmProperties.Endpoint> collectEndpoints() {
        Map<String, AgentLlmProperties.Endpoint> endpoints = new java.util.LinkedHashMap<>();
        if (llmProperties.getEndpoints() != null) {
            endpoints.putAll(llmProperties.getEndpoints());
        }
        AgentLlmProperties local = localConfigLoader.current().orElse(null);
        if (local != null && local.getEndpoints() != null) {
            endpoints.putAll(local.getEndpoints());
        }
        return endpoints;
    }
}

package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.config.RunStageConfig;
import world.willfrog.agent.config.StageLlmConfig;
import world.willfrog.agent.config.SubAgentStageConfig;

/**
 * 阶段级 LLM 配置解析器。
 * <p>
 * 负责从 ext JSON 中解析客户端传入的 stage_config_json，
 * 与 agent-llm.local.json 中的 runtime 配置合并，
 * 生成最终的 RunStageConfig。
 * </p>
 * <p>配置优先级：客户端阶段字段 > agent-llm.local.json 阶段字段</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StageConfigResolver {

    private final AgentLlmProperties llmProperties;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final ObjectMapper objectMapper;

    /**
     * 从 run ext JSON 解析并合并阶段配置。
     *
     * @param extJson run 的 ext 字段 JSON 字符串
     * @return 合并后的阶段配置，不为 null
     */
    public RunStageConfig resolve(String extJson) {
        if (isDebugEnabled()) {
            log.debug("[StageConfigResolver] 开始解析 extJson: {}", extJson);
        }
        RunStageConfig clientConfig = parseClientConfig(extJson);
        if (isDebugEnabled()) {
            log.debug("[StageConfigResolver] 客户端配置解析完成: planning={}, execution={}",
                    clientConfig.getPlanning() != null ? clientConfig.getPlanning() : "null",
                    clientConfig.getExecution() != null ? clientConfig.getExecution() : "null");
        }
        RunStageConfig localConfig = buildLocalConfig();
        if (isDebugEnabled()) {
            log.debug("[StageConfigResolver] Local配置: planning={}",
                    localConfig.getPlanning() != null ? localConfig.getPlanning().getModelName() : "null");
        }
        RunStageConfig merged = mergeConfig(clientConfig, localConfig);
        log.info("[StageConfigResolver] 配置合并完成: planning.endpoint={}, planning.model={}",
                merged.getPlanning() != null ? merged.getPlanning().getEndpointName() : "null",
                merged.getPlanning() != null ? merged.getPlanning().getModelName() : "null");
        return merged;
    }

    /**
     * 检查是否开启阶段级配置 debug 日志。
     * 优先从热加载配置读取，未配置则使用默认值 false。
     */
    private boolean isDebugEnabled() {
        AgentLlmProperties local = localConfigLoader.current().orElse(null);
        if (local != null && local.getDebug() != null && local.getDebug().getLogStageConfig() != null) {
            return local.getDebug().getLogStageConfig();
        }
        return llmProperties.getDebug() != null && Boolean.TRUE.equals(llmProperties.getDebug().getLogStageConfig());
    }

    /**
     * 从 ext JSON 中提取 stage_config_json 并解析为 RunStageConfig。
     */
    private RunStageConfig parseClientConfig(String extJson) {
        if (extJson == null || extJson.isBlank()) {
            if (isDebugEnabled()) {
                log.debug("[StageConfigResolver] extJson 为空");
            }
            return new RunStageConfig();
        }
        try {
            JsonNode root = objectMapper.readTree(extJson);
            if (isDebugEnabled()) {
                log.debug("[StageConfigResolver] extJson 解析为 JSON: {}", root.toString());
            }
            JsonNode stageNode = root.get("stage_config_json");
            if (isDebugEnabled()) {
                log.debug("[StageConfigResolver] stage_config_json 节点: {}", stageNode);
            }
            if (stageNode == null || stageNode.isNull()) {
                log.warn("[StageConfigResolver] stage_config_json 节点为空或不存在");
                return new RunStageConfig();
            }
            // stage_config_json 可能是字符串（需要二次解析）或直接是对象
            String stageJson;
            if (stageNode.isTextual()) {
                stageJson = stageNode.asText();
                if (isDebugEnabled()) {
                    log.debug("[StageConfigResolver] stage_config_json 是字符串: {}", stageJson);
                }
            } else {
                stageJson = stageNode.toString();
                if (isDebugEnabled()) {
                    log.debug("[StageConfigResolver] stage_config_json 是对象: {}", stageJson);
                }
            }
            RunStageConfig config = objectMapper.readValue(stageJson, RunStageConfig.class);
            log.info("[StageConfigResolver] 客户端配置解析成功: planning.endpoint={}, planning.model={}",
                    config.getPlanning() != null ? config.getPlanning().getEndpointName() : "null",
                    config.getPlanning() != null ? config.getPlanning().getModelName() : "null");
            return config;
        } catch (Exception e) {
            log.warn("[StageConfigResolver] 解析客户端 stage_config_json 失败: {}", e.getMessage(), e);
            return new RunStageConfig();
        }
    }

    /**
     * 从 agent-llm.local.json (热加载) 和 base properties 构建 local 阶段配置。
     */
    private RunStageConfig buildLocalConfig() {
        RunStageConfig config = new RunStageConfig();
        AgentLlmProperties local = localConfigLoader.current().orElse(null);

        // 构建 planning 配置
        config.setPlanning(buildPlanningFromProperties(local));
        // 构建 execution 配置（execution 在 local config 中没有独立的 endpoint/model，
        // 使用 defaultEndpoint/defaultModel）
        config.setExecution(buildExecutionFromProperties(local));
        // 构建 sub_agent 配置
        config.setSubAgent(buildSubAgentFromProperties(local));

        return config;
    }

    private StageLlmConfig buildPlanningFromProperties(AgentLlmProperties local) {
        StageLlmConfig cfg = new StageLlmConfig();
        // local 优先
        if (local != null && local.getRuntime() != null && local.getRuntime().getPlanning() != null) {
            AgentLlmProperties.Planning planning = local.getRuntime().getPlanning();
            setIfNotBlank(cfg, planning.getEndpointName(), planning.getModelName());
            cfg.setReasoningEffort(resolveReasoningEffort(planning.getReasoning()));
            return cfg;
        }
        // fallback 到 base properties
        if (llmProperties.getRuntime() != null && llmProperties.getRuntime().getPlanning() != null) {
            AgentLlmProperties.Planning planning = llmProperties.getRuntime().getPlanning();
            setIfNotBlank(cfg, planning.getEndpointName(), planning.getModelName());
            cfg.setReasoningEffort(resolveReasoningEffort(planning.getReasoning()));
        }
        return cfg;
    }

    private StageLlmConfig buildExecutionFromProperties(AgentLlmProperties local) {
        StageLlmConfig cfg = new StageLlmConfig();
        // execution 阶段使用 defaultEndpoint/defaultModel 作为默认值
        if (local != null) {
            setIfNotBlank(cfg, local.getDefaultEndpoint(), local.getDefaultModel());
            if (local.getRuntime() != null && local.getRuntime().getExecution() != null) {
                cfg.setReasoningEffort(resolveReasoningEffort(local.getRuntime().getExecution().getReasoning()));
            }
            if (cfg.isValid()) {
                return cfg;
            }
        }
        setIfNotBlank(cfg, llmProperties.getDefaultEndpoint(), llmProperties.getDefaultModel());
        if (llmProperties.getRuntime() != null && llmProperties.getRuntime().getExecution() != null) {
            cfg.setReasoningEffort(resolveReasoningEffort(llmProperties.getRuntime().getExecution().getReasoning()));
        }
        return cfg;
    }

    private SubAgentStageConfig buildSubAgentFromProperties(AgentLlmProperties local) {
        SubAgentStageConfig subCfg = new SubAgentStageConfig();
        AgentLlmProperties.SubAgent subAgent = resolveSubAgentProperties(local);
        if (subAgent == null) {
            return subCfg;
        }

        String baseEndpoint = isNotBlank(subAgent.getEndpointName()) ? subAgent.getEndpointName() : null;
        String baseReasoning = resolveReasoningEffort(subAgent.getReasoning());

        // 低复杂度
        StageLlmConfig low = new StageLlmConfig();
        setIfNotBlank(low, baseEndpoint, subAgent.getLowComplexityModelName());
        // 如果没有专用 model，fallback 到通用 modelName
        if (!low.isValid()) {
            setIfNotBlank(low, baseEndpoint, subAgent.getModelName());
        }
        low.setReasoningEffort(baseReasoning);
        subCfg.setLowComplexity(low);

        // 中复杂度
        StageLlmConfig medium = new StageLlmConfig();
        setIfNotBlank(medium, baseEndpoint, subAgent.getMediumComplexityModelName());
        if (!medium.isValid()) {
            setIfNotBlank(medium, baseEndpoint, subAgent.getModelName());
        }
        medium.setReasoningEffort(baseReasoning);
        subCfg.setMediumComplexity(medium);

        // 高复杂度
        StageLlmConfig high = new StageLlmConfig();
        setIfNotBlank(high, baseEndpoint, subAgent.getHighComplexityModelName());
        if (!high.isValid()) {
            setIfNotBlank(high, baseEndpoint, subAgent.getModelName());
        }
        high.setReasoningEffort(baseReasoning);
        subCfg.setHighComplexity(high);

        return subCfg;
    }

    private AgentLlmProperties.SubAgent resolveSubAgentProperties(AgentLlmProperties local) {
        if (local != null && local.getRuntime() != null && local.getRuntime().getSubAgent() != null) {
            AgentLlmProperties.SubAgent sa = local.getRuntime().getSubAgent();
            if (isNotBlank(sa.getEndpointName()) || isNotBlank(sa.getModelName())) {
                return sa;
            }
        }
        if (llmProperties.getRuntime() != null) {
            return llmProperties.getRuntime().getSubAgent();
        }
        return null;
    }

    /**
     * 合并客户端配置和本地配置：客户端配置优先，缺失的字段 fallback 到本地配置。
     */
    private RunStageConfig mergeConfig(RunStageConfig client, RunStageConfig local) {
        RunStageConfig merged = new RunStageConfig();

        // planning
        merged.setPlanning(mergeStageLlmConfig(
                client == null ? null : client.getPlanning(),
                local == null ? null : local.getPlanning()));

        // execution
        merged.setExecution(mergeStageLlmConfig(
                client == null ? null : client.getExecution(),
                local == null ? null : local.getExecution()));

        // sub_agent
        merged.setSubAgent(mergeSubAgentConfig(
                client == null ? null : client.getSubAgent(),
                local == null ? null : local.getSubAgent()));

        return merged;
    }

    private StageLlmConfig mergeStageLlmConfig(StageLlmConfig client, StageLlmConfig local) {
        if (!hasAnyStageField(client)) {
            return copyStageConfig(local);
        }
        StageLlmConfig merged = new StageLlmConfig();
        merged.setEndpointName(firstNonBlank(client.getEndpointName(), local == null ? null : local.getEndpointName()));
        merged.setModelName(firstNonBlank(client.getModelName(), local == null ? null : local.getModelName()));
        merged.setReasoningEffort(firstNonBlank(client.getReasoningEffort(), local == null ? null : local.getReasoningEffort()));
        merged.setTemperature(client.getTemperature() != null ? client.getTemperature() : (local == null ? null : local.getTemperature()));
        merged.setMaxTokens(client.getMaxTokens() != null ? client.getMaxTokens() : (local == null ? null : local.getMaxTokens()));
        return merged;
    }

    private SubAgentStageConfig mergeSubAgentConfig(SubAgentStageConfig client, SubAgentStageConfig local) {
        if (client == null && local == null) {
            return new SubAgentStageConfig();
        }
        SubAgentStageConfig merged = new SubAgentStageConfig();
        merged.setLowComplexity(mergeStageLlmConfig(
                client == null ? null : client.getLowComplexity(),
                local == null ? null : local.getLowComplexity()));
        merged.setMediumComplexity(mergeStageLlmConfig(
                client == null ? null : client.getMediumComplexity(),
                local == null ? null : local.getMediumComplexity()));
        merged.setHighComplexity(mergeStageLlmConfig(
                client == null ? null : client.getHighComplexity(),
                local == null ? null : local.getHighComplexity()));
        return merged;
    }

    private String resolveReasoningEffort(AgentLlmProperties.Reasoning reasoning) {
        if (reasoning == null) {
            return null;
        }
        return reasoning.resolveEffort();
    }

    private void setIfNotBlank(StageLlmConfig cfg, String endpointName, String modelName) {
        if (isNotBlank(endpointName)) {
            cfg.setEndpointName(endpointName.trim());
        }
        if (isNotBlank(modelName)) {
            cfg.setModelName(modelName.trim());
        }
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String firstNonBlank(String first, String second) {
        return isNotBlank(first) ? first.trim() : (isNotBlank(second) ? second.trim() : null);
    }

    private boolean hasAnyStageField(StageLlmConfig config) {
        return config != null
                && (isNotBlank(config.getEndpointName())
                || isNotBlank(config.getModelName())
                || isNotBlank(config.getReasoningEffort())
                || config.getTemperature() != null
                || config.getMaxTokens() != null);
    }

    private StageLlmConfig copyStageConfig(StageLlmConfig source) {
        StageLlmConfig copy = new StageLlmConfig();
        if (source == null) {
            return copy;
        }
        copy.setEndpointName(firstNonBlank(source.getEndpointName(), null));
        copy.setModelName(firstNonBlank(source.getModelName(), null));
        copy.setReasoningEffort(firstNonBlank(source.getReasoningEffort(), null));
        copy.setTemperature(source.getTemperature());
        copy.setMaxTokens(source.getMaxTokens());
        return copy;
    }
}

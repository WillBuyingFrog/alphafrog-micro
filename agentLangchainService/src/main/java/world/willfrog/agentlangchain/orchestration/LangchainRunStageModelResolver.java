package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.RunStageConfig;
import world.willfrog.agent.platform.config.StageLlmConfig;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.service.AgentAiServiceFactory;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentLlmResolver;
import world.willfrog.agent.platform.service.StageConfigResolver;
import world.willfrog.agent.platform.service.StageConfigValidator;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves planning / execution / final-answer ChatModels for a run,
 * mirroring legacy {@code AgentRunExecutor} stage selection semantics.
 */
@Component
@RequiredArgsConstructor
public class LangchainRunStageModelResolver {

    private final StageConfigResolver stageConfigResolver;
    private final StageConfigValidator stageConfigValidator;
    private final AgentAiServiceFactory aiServiceFactory;
    private final AgentEventService eventService;
    private final ObjectMapper objectMapper;

    public StageModels resolve(AgentRun run) {
        RunStageConfig stageConfig = stageConfigResolver.resolve(run.getExt());
        stageConfigValidator.validate(stageConfig);

        String requestedEndpointName = eventService.extractEndpointName(run.getExt());
        String requestedModelName = eventService.extractModelName(run.getExt());
        var userProviderOrder = eventService.extractOpenRouterProviderOrder(run.getExt());

        StageLlmConfig execStageCfg = chooseEffectiveStageConfig(
                requestedEndpointName, requestedModelName, stageConfig.getExecution(), run.getExt(), "execution");
        AgentLlmResolver.ResolvedLlm resolvedLlm = aiServiceFactory.resolveLlm(
                firstNonBlank(execStageCfg.getEndpointName(), requestedEndpointName),
                firstNonBlank(execStageCfg.getModelName(), requestedModelName));
        var providerOrder = mergeProviderOrder(
                resolveStageProviderOrder(execStageCfg, userProviderOrder), resolvedLlm.validProviders());
        ChatModel executionModel = aiServiceFactory.buildChatModelWithProviderOrder(
                resolvedLlm, providerOrder, execStageCfg.getMaxTokens());

        StageLlmConfig planningStageCfg = chooseEffectiveStageConfig(
                requestedEndpointName, requestedModelName, stageConfig.getPlanning(), run.getExt(), "planning");
        ChatModel planningModel = executionModel;
        if (planningStageCfg != null && planningStageCfg.isValid()) {
            AgentLlmResolver.ResolvedLlm planningResolved = aiServiceFactory.resolveLlm(
                    planningStageCfg.getEndpointName(), planningStageCfg.getModelName());
            var planningProviderOrder = mergeProviderOrder(
                    resolveStageProviderOrder(planningStageCfg, userProviderOrder), planningResolved.validProviders());
            planningModel = aiServiceFactory.buildChatModelWithProviderOrder(
                    planningResolved, planningProviderOrder, planningStageCfg.getMaxTokens());
        }

        ChatModel finalAnswerModel = executionModel;
        StageLlmConfig finalAnswerStageCfg = chooseEffectiveStageConfig(
                requestedEndpointName, requestedModelName, stageConfig.getFinalAnswer(), run.getExt(), "final_answer");
        if (hasAnyStageField(stageConfig.getFinalAnswer()) && finalAnswerStageCfg != null && finalAnswerStageCfg.isValid()) {
            AgentLlmResolver.ResolvedLlm finalResolved = aiServiceFactory.resolveLlm(
                    finalAnswerStageCfg.getEndpointName(), finalAnswerStageCfg.getModelName());
            var finalProviderOrder = mergeProviderOrder(
                    resolveStageProviderOrder(finalAnswerStageCfg, userProviderOrder), finalResolved.validProviders());
            finalAnswerModel = aiServiceFactory.buildChatModelWithProviderOrder(
                    finalResolved, finalProviderOrder, finalAnswerStageCfg.getMaxTokens());
        }

        return new StageModels(planningModel, executionModel, finalAnswerModel);
    }

    public record StageModels(ChatModel planningModel, ChatModel executionModel, ChatModel finalAnswerModel) {
    }

    private StageLlmConfig chooseEffectiveStageConfig(String requestedEndpointName,
                                                        String requestedModelName,
                                                        StageLlmConfig fallback,
                                                        String extJson,
                                                        String stageName) {
        StageLlmConfig clientStage = parseClientStageConfig(extJson, stageName);
        StageLlmConfig effective = new StageLlmConfig();
        effective.setEndpointName(firstNonBlank(
                clientStage == null ? null : clientStage.getEndpointName(),
                firstNonBlank(requestedEndpointName, fallback == null ? null : fallback.getEndpointName())));
        effective.setModelName(firstNonBlank(
                clientStage == null ? null : clientStage.getModelName(),
                firstNonBlank(requestedModelName, fallback == null ? null : fallback.getModelName())));
        effective.setReasoningEffort(firstNonBlank(
                clientStage == null ? null : clientStage.getReasoningEffort(),
                fallback == null ? null : fallback.getReasoningEffort()));
        effective.setTemperature(clientStage != null && clientStage.getTemperature() != null
                ? clientStage.getTemperature()
                : (fallback == null ? null : fallback.getTemperature()));
        effective.setMaxTokens(clientStage != null && clientStage.getMaxTokens() != null
                ? clientStage.getMaxTokens()
                : (fallback == null ? null : fallback.getMaxTokens()));
        effective.setProviderOrder(clientStage != null && clientStage.getProviderOrder() != null && !clientStage.getProviderOrder().isEmpty()
                ? clientStage.getProviderOrder()
                : (fallback == null ? null : fallback.getProviderOrder()));
        return effective;
    }

    private StageLlmConfig parseClientStageConfig(String extJson, String stageName) {
        if (extJson == null || extJson.isBlank() || stageName == null || stageName.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(extJson);
            JsonNode stageNode = root.get("stage_config_json");
            if (stageNode == null || stageNode.isNull()) {
                return null;
            }
            if (stageNode.isTextual()) {
                stageNode = objectMapper.readTree(stageNode.asText());
            }
            JsonNode phaseNode = stageNode.get(stageName);
            if (phaseNode == null || !phaseNode.isObject()) {
                return null;
            }
            return objectMapper.treeToValue(phaseNode, StageLlmConfig.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> mergeProviderOrder(List<String> userProviders, List<String> validProviders) {
        if (validProviders == null || validProviders.isEmpty()) {
            return userProviders == null ? List.of() : userProviders;
        }
        if (userProviders == null || userProviders.isEmpty()) {
            return validProviders;
        }
        List<String> merged = new ArrayList<>(userProviders);
        for (String vp : validProviders) {
            if (!merged.contains(vp)) {
                merged.add(vp);
            }
        }
        return merged;
    }

    private List<String> resolveStageProviderOrder(StageLlmConfig stageCfg, List<String> userProviderOrder) {
        if (stageCfg != null && stageCfg.getProviderOrder() != null && !stageCfg.getProviderOrder().isEmpty()) {
            return stageCfg.getProviderOrder();
        }
        return userProviderOrder == null ? List.of() : userProviderOrder;
    }

    private boolean hasAnyStageField(StageLlmConfig config) {
        if (config == null) {
            return false;
        }
        return !isBlank(config.getEndpointName())
                || !isBlank(config.getModelName())
                || config.getMaxTokens() != null
                || config.getTemperature() != null
                || !isBlank(config.getReasoningEffort())
                || (config.getProviderOrder() != null && !config.getProviderOrder().isEmpty());
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

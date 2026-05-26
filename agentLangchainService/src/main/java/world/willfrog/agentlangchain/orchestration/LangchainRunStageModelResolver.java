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
 * 为一次 agent run 解析 planning（规划）、execution（执行）、final-answer（最终答案）三个阶段各自使用的 ChatModel。
 *
 * <p>面试高频问题「planning 用什么模型、execution 用什么模型、final answer 用什么模型」的核心答案文件。
 * 它的职责不是直接构造模型，而是把三层配置源合并成每个阶段的 Effective Config（生效配置），
 * 再委托 {@link AgentAiServiceFactory} 去实际 build ChatModel。</p>
 *
 * <p>三层配置源的优先级（从高到低）：</p>
 * <ol>
 *   <li><b>客户端 stage_config_json</b>：用户请求 ext 字段中携带的 per-stage 配置（endpoint/model/maxTokens/temperature 等），
 *       通过 {@link #parseClientStageConfig} 解析；</li>
 *   <li><b>运行时请求参数</b>：用户请求中显式指定的 endpointName / modelName / providerOrder，
 *       通过 {@link AgentEventService#extractEndpointName} / {@link AgentEventService#extractModelName} 提取；</li>
 *   <li><b>Nacos / classpath 默认配置</b>：{@link RunStageConfig} 中定义的 execution / planning / finalAnswer fallback 配置，
 *       由 {@link StageConfigResolver} 解析、{@link StageConfigValidator} 校验。</li>
 * </ol>
 *
 * <p>三阶段默认行为：如果 planning 或 final-answer 没有独立配置，则<strong>退化（fallback）为使用 execution 阶段模型</strong>。
 * 这意味着大多数简单场景只需要配置一个 execution 模型即可，复杂场景才需要为不同阶段分配不同模型
 *（例如 planning 用轻量快模型、execution 用强模型）。</p>
 *
 * <p>provider order（供应商优先级）的合并逻辑：
 * 用户指定的 providerOrder（来自请求或 stage config）排在前面，
 * endpoint 级模型元数据中的 validProviders（来自 {@link AgentLlmResolver}）补充到后面，
 * 确保用户偏好优先、系统白名单兜底。</p>
 */
@Component
@RequiredArgsConstructor
public class LangchainRunStageModelResolver {

    private final StageConfigResolver stageConfigResolver;
    private final StageConfigValidator stageConfigValidator;
    private final AgentAiServiceFactory aiServiceFactory;
    private final AgentEventService eventService;
    private final ObjectMapper objectMapper;

    /**
     * 解析一次 run 的三阶段 ChatModel。
     *
     * <p>执行顺序：</p>
     * <ol>
     *   <li>从 run.ext 解析并校验 {@link RunStageConfig}（execution / planning / finalAnswer 的 fallback 配置）；</li>
     *   <li>提取用户请求中的 endpointName、modelName、providerOrder；</li>
     *   <li>为 execution 阶段：合并三层配置 → 调用 {@link AgentAiServiceFactory#resolveLlm} 解析 endpoint/baseUrl/model
     *       → 调用 {@link AgentAiServiceFactory#buildChatModelWithProviderOrder} 构建 ChatModel；</li>
     *   <li>为 planning 阶段：如果 stage config 中有独立的 planning 配置，则独立解析模型；否则退化为 execution 模型；</li>
     *   <li>为 final-answer 阶段：如果 stage config 的 finalAnswer 中有任何字段被配置，则独立解析模型；否则退化为 execution 模型。</li>
     * </ol>
     *
     * <p>为什么 final-answer 的判断条件是 {@link #hasAnyStageField} 而不是 isValid？
     * 因为 final-answer 阶段很多场景不需要独立配置（直接用 execution 模型写答案即可），
     * 只有当用户明确配置了至少一个字段时，才认为「需要独立模型」。</p>
     *
     * @param run 数据库中的 AgentRun 记录，ext 字段携带用户请求和 stage 配置
     * @return 包含三阶段 ChatModel 和 planning 阶段元信息的 StageModels
     */
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
        String planningEndpointName = firstNonBlank(execStageCfg.getEndpointName(), requestedEndpointName);
        String planningModelName = firstNonBlank(execStageCfg.getModelName(), requestedModelName);
        List<String> planningProviderOrder = providerOrder;
        if (planningStageCfg != null && planningStageCfg.isValid()) {
            AgentLlmResolver.ResolvedLlm planningResolved = aiServiceFactory.resolveLlm(
                    planningStageCfg.getEndpointName(), planningStageCfg.getModelName());
            planningProviderOrder = mergeProviderOrder(
                    resolveStageProviderOrder(planningStageCfg, userProviderOrder), planningResolved.validProviders());
            planningModel = aiServiceFactory.buildChatModelWithProviderOrder(
                    planningResolved, planningProviderOrder, planningStageCfg.getMaxTokens());
            planningEndpointName = firstNonBlank(planningStageCfg.getEndpointName(), requestedEndpointName);
            planningModelName = firstNonBlank(planningStageCfg.getModelName(), requestedModelName);
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

        return new StageModels(
                planningModel,
                executionModel,
                finalAnswerModel,
                planningEndpointName,
                planningModelName,
                planningProviderOrder);
    }

    /**
     * 三阶段模型 + planning 阶段元信息的包装 record。
     *
     * <p>为什么只返回 planning 的 endpointName / modelName / providerOrder，
     * 不返回 execution 和 final-answer 的？
     * 因为 observability 和 event 系统主要关注 planning 阶段的模型信息（它决定了计划的生成质量），
     * execution 和 final-answer 的模型信息可以在需要时从 run.ext 中重新提取。</p>
     */
    public record StageModels(
            ChatModel planningModel,
            ChatModel executionModel,
            ChatModel finalAnswerModel,
            String planningEndpointName,
            String planningModelName,
            List<String> planningProviderOrder) {
    }

    /**
     * 合并三层配置源，生成某个阶段的 Effective Config（生效配置）。
     *
     * <p>优先级：client stage_config_json > 请求参数 > fallback 默认配置。</p>
     *
     * <p>每个字段独立按优先级取第一个非空值：
     * endpointName / modelName / reasoningEffort 用字符串优先级；
     * temperature / maxTokens 用非空对象优先级；
     * providerOrder 用非空列表优先级。</p>
     *
     * @param requestedEndpointName 请求中显式指定的 endpoint 名称
     * @param requestedModelName    请求中显式指定的模型名称
     * @param fallback              stage config 中的 fallback 配置
     * @param extJson               run.ext 中的原始 JSON，可能包含客户端 stage_config_json
     * @param stageName             阶段名称（execution / planning / final_answer）
     * @return 合并后的 Effective Config
     */
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

    /**
     * 从 run.ext JSON 中解析客户端传入的 stage_config_json。
     *
     * <p>ext 字段是一个灵活的 JSON 字符串，客户端可以在其中嵌入各种运行时配置。
     * stage_config_json 的格式示例：
     * <pre>{@code
     * {
     *   "stage_config_json": {
     *     "execution":   { "endpointName": "openrouter", "modelName": "gpt-4o", "maxTokens": 100000 },
     *     "planning":    { "endpointName": "openrouter", "modelName": "deepseek-chat" },
     *     "final_answer":{ "maxTokens": 50000 }
     *   }
     * }
     * }</pre></p>
     *
     * <p>注意：stage_config_json 的值可能是 JSON 对象直接嵌入，也可能是字符串形式的 JSON（需要二次解析）。</p>
     *
     * @param extJson   run.ext 原始 JSON 字符串
     * @param stageName 阶段名称
     * @return 解析出的 StageLlmConfig，若不存在或解析失败则返回 null
     */
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

    /**
     * 合并用户指定的 provider order 和系统白名单 validProviders。
     *
     * <p>合并策略：用户偏好优先，系统白名单补充。</p>
     * <ol>
     *   <li>若用户没有指定 providerOrder，直接使用 validProviders；</li>
     *   <li>若系统没有 validProviders，直接使用用户的 providerOrder；</li>
     *   <li>若两者都有：用户指定的 providers 排在前面，validProviders 中不在用户列表中的补充到后面。</li>
     * </ol>
     *
     * <p>这样设计的目的：让用户可以用 providerOrder 指定「首选供应商」，
     * 同时 validProviders 作为安全白名单防止用户指定不支持的供应商。</p>
     */
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

    /**
     * 解析某个阶段的 provider order。
     *
     * <p>优先级：阶段级 providerOrder（来自 stage config 或 client stage_config_json）
     * > 用户请求级 providerOrder（来自 run.ext 的 openRouterProviderOrder）。</p>
     */
    private List<String> resolveStageProviderOrder(StageLlmConfig stageCfg, List<String> userProviderOrder) {
        if (stageCfg != null && stageCfg.getProviderOrder() != null && !stageCfg.getProviderOrder().isEmpty()) {
            return stageCfg.getProviderOrder();
        }
        return userProviderOrder == null ? List.of() : userProviderOrder;
    }

    /**
     * 判断 final-answer 阶段的 fallback 配置中是否有任何字段被设置。
     *
     * <p>final-answer 的特殊性：它和 execution 的默认模型通常是同一个，
     * 只有当用户明确配置了至少一个字段（endpointName / modelName / maxTokens / temperature / reasoningEffort / providerOrder）时，
     * 才认为需要为 final-answer 单独解析模型。</p>
     */
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

    /**
     * 取第一个非空白的字符串值，用于实现多级 fallback。
     */
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

package world.willfrog.agentlangchain.planning;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads Nacos/local planning structured-output flags for langchain planner parity with legacy {@code TodoPlanner}.
 */
@Component
@RequiredArgsConstructor
public class LangchainPlanningStructuredOutputSettings {

    private final AgentLlmProperties llmProperties;
    private final AgentLlmLocalConfigLoader localConfigLoader;

    public boolean structuredEnabled() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getEnabled);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getEnabled)
                .orElse(null);
        return base == null || base;
    }

    public boolean structuredStrict() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrict);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrict)
                .orElse(null);
        return Boolean.TRUE.equals(base);
    }

    /**
     * OpenRouter: do not set {@code provider.require_parameters=true} for planning — it narrows routing
     * to providers that natively support every request field (often only deepseek for Kimi), which
     * conflicts with explicit client provider order.
     */
    public boolean requireProviderParameters(String planningEndpointName) {
        if (isOpenRouterPlanningEndpoint(planningEndpointName)) {
            return false;
        }
        return requireProviderParametersFromConfig();
    }

    private boolean requireProviderParametersFromConfig() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getRequireProviderParameters);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getRequireProviderParameters)
                .orElse(null);
        return base == null || base;
    }

    private static boolean isOpenRouterPlanningEndpoint(String planningEndpointName) {
        return planningEndpointName != null
                && "openrouter".equalsIgnoreCase(planningEndpointName.trim());
    }

    public boolean allowProviderFallbacks() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getAllowProviderFallbacks);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getAllowProviderFallbacks)
                .orElse(null);
        return base != null && base;
    }

    /**
     * JSON schema aligned with legacy {@code StructuredPlanningSupport#todoPlanningJsonSchema()}.
     */
    public boolean strategyStageEnabled() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrategyStageEnabled);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrategyStageEnabled)
                .orElse(null);
        return base == null || base;
    }

    public int strategyMaxDetailLength() {
        int local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrategyMaxDetailLength)
                .orElse(0);
        if (local > 0) {
            return local;
        }
        int base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrategyMaxDetailLength)
                .orElse(0);
        return base > 0 ? base : 500;
    }

    public int resolveMaxTodos(int defaultMaxTodos) {
        int local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getMaxTodos)
                .orElse(0);
        if (local > 0) {
            return clamp(local, 1, 50);
        }
        int base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getMaxTodos)
                .orElse(0);
        if (base > 0) {
            return clamp(base, 1, 50);
        }
        return clamp(defaultMaxTodos, 1, 50);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    public Map<String, Object> todoPlanningJsonSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("analysis", "items"),
                "properties", Map.of(
                        "analysis", Map.of("type", "string"),
                        "extractedEntities", Map.of(
                                "type", "array",
                                "description", "用户明确提到的金融实体、指数、基金或股票名称。",
                                "items", Map.of("type", "string")
                        ),
                        "items", Map.of(
                                "type", "array",
                                "minItems", 1,
                                "items", Map.of(
                                        "type", "object",
                                        "additionalProperties", false,
                                        "required", List.of("id", "sequence", "description"),
                                        "properties", Map.of(
                                                "id", Map.of("type", "string"),
                                                "sequence", Map.of("type", "integer"),
                                                "description", Map.of(
                                                        "type", "string",
                                                        "description", "1-3句话描述该Todo要完成的任务"
                                                ),
                                                "dependsOn", Map.of(
                                                        "type", "array",
                                                        "description", "依赖的todoId列表（DAG模式下可选）",
                                                        "items", Map.of("type", "string")
                                                ),
                                                "groupKey", Map.of(
                                                        "type", "string",
                                                        "description", "可选：并行分组键"
                                                ),
                                                "parallelizable", Map.of(
                                                        "type", "boolean",
                                                        "description", "可选：该节点是否可并行"
                                                )
                                        )
                                )
                        )
                )
        );
    }
}

package world.willfrog.agent.service;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.config.AgentLlmProperties;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class JudgeModelSelectorService {

    private final AgentLlmProperties properties;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final AgentLlmResolver llmResolver;

    public List<Selection> selectCandidates() {
        AgentLlmProperties local = localConfigLoader.current().orElse(null);
        AgentLlmProperties.Judge judge = chooseJudge(properties, local);
        if (judge == null || judge.getRoutes() == null || judge.getRoutes().isEmpty()) {
            return List.of();
        }
        List<Selection> candidates = new ArrayList<>();
        for (AgentLlmProperties.JudgeRoute route : judge.getRoutes()) {
            if (route == null || isBlank(route.getEndpointName()) || route.getModels() == null) {
                continue;
            }
            for (String model : route.getModels()) {
                if (isBlank(model)) {
                    continue;
                }
                try {
                    AgentLlmResolver.ResolvedLlm resolved = llmResolver.resolve(route.getEndpointName(), model);
                    candidates.add(Selection.builder()
                            .endpointName(resolved.endpointName())
                            .endpointBaseUrl(resolved.baseUrl())
                            .modelName(resolved.modelName())
                            .build());
                } catch (Exception e) {
                    log.warn("Skip invalid judge route endpoint={}, model={}, error={}",
                            route.getEndpointName(), model, e.getMessage());
                }
            }
        }
        return candidates;
    }

    private AgentLlmProperties.Judge chooseJudge(AgentLlmProperties base, AgentLlmProperties local) {
        AgentLlmProperties.Judge localJudge = local == null || local.getRuntime() == null
                ? null
                : local.getRuntime().getJudge();
        if (localJudge != null && localJudge.getRoutes() != null && !localJudge.getRoutes().isEmpty()) {
            return localJudge;
        }
        return base == null || base.getRuntime() == null ? null : base.getRuntime().getJudge();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Data
    @Builder
    public static class Selection {
        private String endpointName;
        private String endpointBaseUrl;
        private String modelName;
    }
}

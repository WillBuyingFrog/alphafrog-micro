package world.willfrog.agent.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import world.willfrog.agent.config.AgentLlmProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentModelCatalogService {

    private static final double DEFAULT_BASE_RATE = 1.0D;

    private final AgentLlmProperties properties;
    private final AgentLlmLocalConfigLoader localConfigLoader;

    public List<ModelCatalogItem> listModels() {
        AgentLlmProperties local = localConfigLoader.current().orElse(null);
        Map<String, AgentLlmProperties.Endpoint> endpoints = mergeEndpoints(properties, local);
        List<String> allowedModels = chooseModels(properties, local);
        Map<String, AgentLlmProperties.ModelMetadata> metadataMap = mergeModelMetadata(properties, local);
        List<AgentLlmProperties.JudgeRoute> routes = chooseRoutes(properties, local);

        LinkedHashMap<String, ModelCatalogItem> items = new LinkedHashMap<>();
        if (!routes.isEmpty()) {
            for (AgentLlmProperties.JudgeRoute route : routes) {
                if (route == null) {
                    continue;
                }
                String endpoint = normalize(route.getEndpointName());
                if (endpoint == null || !endpoints.containsKey(endpoint)) {
                    continue;
                }
                for (String model : route.getModels() == null ? List.<String>of() : route.getModels()) {
                    String modelId = normalize(model);
                    if (modelId == null) {
                        continue;
                    }
                    if (!allowedModels.isEmpty() && !allowedModels.contains(modelId)) {
                        continue;
                    }
                    addItem(items, metadataMap, modelId, endpoint);
                }
            }
        }

        if (items.isEmpty()) {
            for (String endpoint : endpoints.keySet()) {
                for (String modelId : allowedModels) {
                    addItem(items, metadataMap, modelId, endpoint);
                }
            }
        }

        return new ArrayList<>(items.values());
    }

    public double resolveBaseRate(String modelId) {
        String normalized = normalize(modelId);
        if (normalized == null) {
            return DEFAULT_BASE_RATE;
        }
        AgentLlmProperties local = localConfigLoader.current().orElse(null);
        Map<String, AgentLlmProperties.ModelMetadata> metadata = mergeModelMetadata(properties, local);
        AgentLlmProperties.ModelMetadata meta = metadata.get(normalized);
        if (meta == null || meta.getBaseRate() == null || meta.getBaseRate() <= 0D) {
            return DEFAULT_BASE_RATE;
        }
        return meta.getBaseRate();
    }

    private void addItem(Map<String, ModelCatalogItem> items,
                         Map<String, AgentLlmProperties.ModelMetadata> metadataMap,
                         String modelId,
                         String endpoint) {
        String compositeId = modelId + "@" + endpoint;
        AgentLlmProperties.ModelMetadata metadata = metadataMap.get(modelId);
        String displayName = metadata == null || isBlank(metadata.getDisplayName())
                ? modelId
                : metadata.getDisplayName().trim();
        double baseRate = metadata == null || metadata.getBaseRate() == null || metadata.getBaseRate() <= 0D
                ? DEFAULT_BASE_RATE
                : metadata.getBaseRate();
        List<String> features = metadata == null || metadata.getFeatures() == null
                ? List.of()
                : metadata.getFeatures().stream().map(this::normalize).filter(v -> v != null).toList();
        items.put(compositeId, new ModelCatalogItem(
                modelId,
                displayName,
                endpoint,
                compositeId,
                baseRate,
                features
        ));
    }

    private Map<String, AgentLlmProperties.Endpoint> mergeEndpoints(AgentLlmProperties base, AgentLlmProperties local) {
        Map<String, AgentLlmProperties.Endpoint> merged = new LinkedHashMap<>();
        if (base != null && base.getEndpoints() != null) {
            for (Map.Entry<String, AgentLlmProperties.Endpoint> entry : base.getEndpoints().entrySet()) {
                String key = normalize(entry.getKey());
                if (key == null) {
                    continue;
                }
                merged.put(key, copyEndpoint(entry.getValue()));
            }
        }
        if (local != null && local.getEndpoints() != null) {
            for (Map.Entry<String, AgentLlmProperties.Endpoint> entry : local.getEndpoints().entrySet()) {
                String key = normalize(entry.getKey());
                if (key == null) {
                    continue;
                }
                AgentLlmProperties.Endpoint source = entry.getValue();
                AgentLlmProperties.Endpoint target = merged.computeIfAbsent(key, ignored -> new AgentLlmProperties.Endpoint());
                if (source != null && !isBlank(source.getBaseUrl())) {
                    target.setBaseUrl(source.getBaseUrl().trim());
                }
                if (source != null && !isBlank(source.getApiKey())) {
                    target.setApiKey(source.getApiKey().trim());
                }
            }
        }
        merged.entrySet().removeIf(entry -> entry.getValue() == null || isBlank(entry.getValue().getBaseUrl()));
        return merged;
    }

    private List<String> chooseModels(AgentLlmProperties base, AgentLlmProperties local) {
        List<String> source = local != null && local.getModels() != null && !local.getModels().isEmpty()
                ? local.getModels()
                : (base == null ? List.of() : base.getModels());
        LinkedHashSet<String> deduplicated = new LinkedHashSet<>();
        for (String model : source == null ? List.<String>of() : source) {
            String normalized = normalize(model);
            if (normalized != null) {
                deduplicated.add(normalized);
            }
        }
        return new ArrayList<>(deduplicated);
    }

    private List<AgentLlmProperties.JudgeRoute> chooseRoutes(AgentLlmProperties base, AgentLlmProperties local) {
        List<AgentLlmProperties.JudgeRoute> localRoutes = local == null || local.getRuntime() == null
                || local.getRuntime().getJudge() == null
                ? List.of()
                : local.getRuntime().getJudge().getRoutes();
        if (localRoutes != null && !localRoutes.isEmpty()) {
            return localRoutes;
        }
        List<AgentLlmProperties.JudgeRoute> baseRoutes = base == null || base.getRuntime() == null
                || base.getRuntime().getJudge() == null
                ? List.of()
                : base.getRuntime().getJudge().getRoutes();
        return baseRoutes == null ? List.of() : baseRoutes;
    }

    private Map<String, AgentLlmProperties.ModelMetadata> mergeModelMetadata(AgentLlmProperties base, AgentLlmProperties local) {
        Map<String, AgentLlmProperties.ModelMetadata> merged = new LinkedHashMap<>();
        if (base != null && base.getModelMetadata() != null) {
            for (Map.Entry<String, AgentLlmProperties.ModelMetadata> entry : base.getModelMetadata().entrySet()) {
                String key = normalize(entry.getKey());
                if (key == null) {
                    continue;
                }
                merged.put(key, copyModelMetadata(entry.getValue()));
            }
        }
        if (local != null && local.getModelMetadata() != null) {
            for (Map.Entry<String, AgentLlmProperties.ModelMetadata> entry : local.getModelMetadata().entrySet()) {
                String key = normalize(entry.getKey());
                if (key == null) {
                    continue;
                }
                AgentLlmProperties.ModelMetadata source = entry.getValue();
                AgentLlmProperties.ModelMetadata target = merged.computeIfAbsent(key, ignored -> new AgentLlmProperties.ModelMetadata());
                if (source != null && !isBlank(source.getDisplayName())) {
                    target.setDisplayName(source.getDisplayName().trim());
                }
                if (source != null && source.getBaseRate() != null && source.getBaseRate() > 0D) {
                    target.setBaseRate(source.getBaseRate());
                }
                if (source != null && source.getFeatures() != null && !source.getFeatures().isEmpty()) {
                    target.setFeatures(source.getFeatures());
                }
            }
        }
        return merged;
    }

    private AgentLlmProperties.Endpoint copyEndpoint(AgentLlmProperties.Endpoint source) {
        AgentLlmProperties.Endpoint target = new AgentLlmProperties.Endpoint();
        if (source != null) {
            target.setBaseUrl(source.getBaseUrl());
            target.setApiKey(source.getApiKey());
        }
        return target;
    }

    private AgentLlmProperties.ModelMetadata copyModelMetadata(AgentLlmProperties.ModelMetadata source) {
        AgentLlmProperties.ModelMetadata target = new AgentLlmProperties.ModelMetadata();
        if (source != null) {
            target.setDisplayName(source.getDisplayName());
            target.setBaseRate(source.getBaseRate());
            target.setFeatures(source.getFeatures());
        }
        return target;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public record ModelCatalogItem(
            String id,
            String displayName,
            String endpoint,
            String compositeId,
            double baseRate,
            List<String> features
    ) {
    }
}

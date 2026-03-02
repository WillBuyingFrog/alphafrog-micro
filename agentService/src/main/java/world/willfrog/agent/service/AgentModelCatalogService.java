package world.willfrog.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.config.AgentLlmProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentModelCatalogService {

    private static final double DEFAULT_BASE_RATE = 1.0D;

    private final AgentLlmProperties properties;
    private final AgentLlmLocalConfigLoader localConfigLoader;

    public List<ModelCatalogItem> listModels() {
        AgentLlmProperties local = localConfigLoader.current().orElse(null);
        Map<String, AgentLlmProperties.Endpoint> endpoints = mergeEndpoints(properties, local);
        List<String> allowedModels = chooseModels(properties, local, endpoints);
        log.info("listModels: endpoints={}, allowedModels={}", endpoints.keySet(), allowedModels);
        Map<String, AgentLlmProperties.ModelMetadata> metadataMap = mergeModelMetadata(properties, local, endpoints);
        Map<String, List<String>> endpointModels = listEndpointModels(endpoints);

        LinkedHashMap<String, ModelCatalogItem> items = new LinkedHashMap<>();
        
        // 构建模型 -> endpoint 的映射
        // 优先根据模型在哪个 endpoint 的 models 中定义来确定
        Map<String, String> modelEndpointMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : endpointModels.entrySet()) {
            String endpoint = entry.getKey();
            for (String modelId : entry.getValue()) {
                if (!allowedModels.isEmpty() && !allowedModels.contains(modelId)) {
                    continue;
                }
                // 优先保留第一个遇到的 endpoint（或按配置顺序）
                modelEndpointMap.putIfAbsent(modelId, endpoint);
            }
        }
        
        // 对于未在 endpointModels 中找到的 allowedModels，使用 defaultEndpoint
        String defaultEndpoint = local != null && !isBlank(local.getDefaultEndpoint()) 
                ? normalize(local.getDefaultEndpoint()) 
                : endpoints.keySet().iterator().next();
        
        // 添加所有允许使用的模型
        for (String modelId : allowedModels) {
            String endpoint = modelEndpointMap.getOrDefault(modelId, defaultEndpoint);
            addItem(items, metadataMap, endpoints, modelId, endpoint);
        }

        log.info("listModels: Returning {} items, keys={}", items.size(), items.keySet());
        return new ArrayList<>(items.values());
    }

    public double resolveBaseRate(String modelId) {
        String normalized = normalize(modelId);
        if (normalized == null) {
            return DEFAULT_BASE_RATE;
        }
        AgentLlmProperties local = localConfigLoader.current().orElse(null);
        Map<String, AgentLlmProperties.Endpoint> endpoints = mergeEndpoints(properties, local);
        Map<String, AgentLlmProperties.ModelMetadata> metadata = mergeModelMetadata(properties, local, endpoints);
        AgentLlmProperties.ModelMetadata meta = metadata.get(normalized);
        if (meta == null || meta.getBaseRate() == null || meta.getBaseRate() <= 0D) {
            return DEFAULT_BASE_RATE;
        }
        return meta.getBaseRate();
    }

    private void addItem(Map<String, ModelCatalogItem> items,
                         Map<String, AgentLlmProperties.ModelMetadata> metadataMap,
                         Map<String, AgentLlmProperties.Endpoint> endpoints,
                         String modelId,
                         String endpoint) {
        String compositeId = modelId + "@" + endpoint;
        AgentLlmProperties.ModelMetadata metadata = resolveMetadata(metadataMap, endpoints, endpoint, modelId);
        String displayName = metadata == null || isBlank(metadata.getDisplayName())
                ? modelId
                : metadata.getDisplayName().trim();
        double baseRate = metadata == null || metadata.getBaseRate() == null || metadata.getBaseRate() <= 0D
                ? DEFAULT_BASE_RATE
                : metadata.getBaseRate();
        List<String> features = metadata == null || metadata.getFeatures() == null
                ? List.of()
                : metadata.getFeatures().stream().map(this::normalize).filter(v -> v != null).toList();
        List<String> validProviders = metadata == null || metadata.getValidProviders() == null
                ? List.of()
                : metadata.getValidProviders().stream().map(this::normalize).filter(v -> v != null).toList();
        items.put(compositeId, new ModelCatalogItem(
                modelId,
                displayName,
                endpoint,
                compositeId,
                baseRate,
                features,
                validProviders
        ));
    }

    private AgentLlmProperties.ModelMetadata resolveMetadata(Map<String, AgentLlmProperties.ModelMetadata> metadataMap,
                                                             Map<String, AgentLlmProperties.Endpoint> endpoints,
                                                             String endpoint,
                                                             String modelId) {
        AgentLlmProperties.Endpoint endpointConfig = endpoints.get(endpoint);
        if (endpointConfig != null && endpointConfig.getModels() != null) {
            AgentLlmProperties.ModelMetadata endpointMeta = endpointConfig.getModels().get(modelId);
            if (endpointMeta != null) {
                return endpointMeta;
            }
        }
        return metadataMap.get(modelId);
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
                if (source != null && !isBlank(source.getRegion())) {
                    target.setRegion(source.getRegion().trim());
                }
                if (source != null && source.getModels() != null && !source.getModels().isEmpty()) {
                    for (Map.Entry<String, AgentLlmProperties.ModelMetadata> modelEntry : source.getModels().entrySet()) {
                        String modelKey = normalize(modelEntry.getKey());
                        if (modelKey == null) {
                            continue;
                        }
                        AgentLlmProperties.ModelMetadata mergedModel = target.getModels()
                                .computeIfAbsent(modelKey, ignored -> new AgentLlmProperties.ModelMetadata());
                        mergeModelMetadataInto(mergedModel, modelEntry.getValue());
                    }
                }
            }
        }
        // 记录被过滤的 endpoint
        List<String> removedEndpoints = new ArrayList<>();
        merged.entrySet().removeIf(entry -> {
            boolean shouldRemove = shouldRemoveEndpoint(entry.getKey(), entry.getValue());
            if (shouldRemove) {
                removedEndpoints.add(entry.getKey());
            }
            return shouldRemove;
        });
        if (!removedEndpoints.isEmpty()) {
            log.warn("Removed endpoints without baseUrl: {}", removedEndpoints);
        }
        log.info("mergeEndpoints result: endpoints={}, modelCounts={}", 
                merged.keySet(),
                merged.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                    Map.Entry::getKey, 
                    e -> e.getValue() != null && e.getValue().getModels() != null ? e.getValue().getModels().size() : 0)));
        return merged;
    }

    private List<String> chooseModels(AgentLlmProperties base,
                                      AgentLlmProperties local,
                                      Map<String, AgentLlmProperties.Endpoint> endpoints) {
        // 1. 优先使用 local.models 列表（如果存在且非空）
        if (local != null && local.getModels() != null && !local.getModels().isEmpty()) {
            LinkedHashSet<String> deduplicated = new LinkedHashSet<>();
            for (String model : local.getModels()) {
                String normalized = normalize(model);
                if (normalized != null) {
                    deduplicated.add(normalized);
                }
            }
            log.debug("Using local.models list: {}", deduplicated);
            return new ArrayList<>(deduplicated);
        }
        
        // 2. 从 merged endpoints 收集模型（已包含 local + base）
        LinkedHashSet<String> deduplicated = new LinkedHashSet<>();
        if (endpoints != null) {
            for (AgentLlmProperties.Endpoint endpoint : endpoints.values()) {
                if (endpoint == null || endpoint.getModels() == null) {
                    continue;
                }
                for (String modelId : endpoint.getModels().keySet()) {
                    String normalized = normalize(modelId);
                    if (normalized != null) {
                        deduplicated.add(normalized);
                    }
                }
            }
        }
        
        if (!deduplicated.isEmpty()) {
            log.info("Collected {} models from endpoints: {}", deduplicated.size(), deduplicated);
            return new ArrayList<>(deduplicated);
        }
        
        // 3. 最后尝试 base.models
        if (base != null && base.getModels() != null) {
            for (String model : base.getModels()) {
                String normalized = normalize(model);
                if (normalized != null) {
                    deduplicated.add(normalized);
                }
            }
            log.debug("Using base.models list: {}", deduplicated);
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

    private Map<String, AgentLlmProperties.ModelMetadata> mergeModelMetadata(AgentLlmProperties base,
                                                                              AgentLlmProperties local,
                                                                              Map<String, AgentLlmProperties.Endpoint> endpoints) {
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
                mergeModelMetadataInto(target, source);
            }
        }
        if (endpoints != null && !endpoints.isEmpty()) {
            for (AgentLlmProperties.Endpoint endpoint : endpoints.values()) {
                if (endpoint == null || endpoint.getModels() == null) {
                    continue;
                }
                for (Map.Entry<String, AgentLlmProperties.ModelMetadata> modelEntry : endpoint.getModels().entrySet()) {
                    String modelId = normalize(modelEntry.getKey());
                    if (modelId == null) {
                        continue;
                    }
                    AgentLlmProperties.ModelMetadata target = merged.computeIfAbsent(modelId, ignored -> new AgentLlmProperties.ModelMetadata());
                    mergeModelMetadataInto(target, modelEntry.getValue());
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
            target.setRegion(source.getRegion());
            if (source.getModels() != null && !source.getModels().isEmpty()) {
                Map<String, AgentLlmProperties.ModelMetadata> copiedModels = new LinkedHashMap<>();
                for (Map.Entry<String, AgentLlmProperties.ModelMetadata> entry : source.getModels().entrySet()) {
                    String modelId = normalize(entry.getKey());
                    if (modelId == null) {
                        continue;
                    }
                    copiedModels.put(modelId, copyModelMetadata(entry.getValue()));
                }
                target.setModels(copiedModels);
            }
        }
        return target;
    }

    private boolean shouldRemoveEndpoint(String endpointName, AgentLlmProperties.Endpoint endpoint) {
        if (endpoint == null) {
            return true;
        }
        if (!isBlank(endpoint.getBaseUrl())) {
            return false;
        }
        return !isRegionOnlyDashScopeEndpoint(endpointName, endpoint);
    }

    private boolean isRegionOnlyDashScopeEndpoint(String endpointName, AgentLlmProperties.Endpoint endpoint) {
        String normalizedEndpoint = normalize(endpointName);
        if (normalizedEndpoint == null || endpoint == null) {
            return false;
        }
        return "dashscope".equalsIgnoreCase(normalizedEndpoint)
                && !isBlank(endpoint.getRegion());
    }

    private AgentLlmProperties.ModelMetadata copyModelMetadata(AgentLlmProperties.ModelMetadata source) {
        AgentLlmProperties.ModelMetadata target = new AgentLlmProperties.ModelMetadata();
        if (source != null) {
            target.setDisplayName(source.getDisplayName());
            target.setBaseRate(source.getBaseRate());
            target.setFeatures(source.getFeatures());
            target.setValidProviders(source.getValidProviders());
        }
        return target;
    }

    private void mergeModelMetadataInto(AgentLlmProperties.ModelMetadata target,
                                        AgentLlmProperties.ModelMetadata source) {
        if (target == null || source == null) {
            return;
        }
        if (!isBlank(source.getDisplayName())) {
            target.setDisplayName(source.getDisplayName().trim());
        }
        if (source.getBaseRate() != null && source.getBaseRate() > 0D) {
            target.setBaseRate(source.getBaseRate());
        }
        if (source.getFeatures() != null && !source.getFeatures().isEmpty()) {
            target.setFeatures(source.getFeatures());
        }
        if (source.getValidProviders() != null && !source.getValidProviders().isEmpty()) {
            target.setValidProviders(source.getValidProviders());
        }
    }

    private Map<String, List<String>> listEndpointModels(Map<String, AgentLlmProperties.Endpoint> endpoints) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (endpoints == null || endpoints.isEmpty()) {
            return result;
        }
        for (Map.Entry<String, AgentLlmProperties.Endpoint> entry : endpoints.entrySet()) {
            String endpoint = normalize(entry.getKey());
            if (endpoint == null) {
                continue;
            }
            AgentLlmProperties.Endpoint config = entry.getValue();
            if (config == null || config.getModels() == null || config.getModels().isEmpty()) {
                continue;
            }
            LinkedHashSet<String> modelIds = new LinkedHashSet<>();
            for (String modelId : config.getModels().keySet()) {
                String normalized = normalize(modelId);
                if (normalized != null) {
                    modelIds.add(normalized);
                }
            }
            if (!modelIds.isEmpty()) {
                result.put(endpoint, new ArrayList<>(modelIds));
            }
        }
        return result;
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
            List<String> features,
            List<String> validProviders
    ) {
    }
}

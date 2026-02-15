package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentLlmLocalConfigLoader {

    private final ObjectMapper objectMapper;

    @Value("${agent.llm.config-file:}")
    private String configFile;

    private volatile AgentLlmProperties localConfig;
    private volatile String loadedConfigPath = "";
    private volatile long loadedConfigLastModified = Long.MIN_VALUE;
    private final Object reloadLock = new Object();

    @PostConstruct
    public void load() {
        reloadIfNeeded(true);
    }

    @Scheduled(fixedDelayString = "${agent.llm.config-refresh-interval-ms:10000}")
    public void refresh() {
        reloadIfNeeded(false);
    }

    private void reloadIfNeeded(boolean force) {
        String file = configFile == null ? "" : configFile.trim();
        if (file.isEmpty()) {
            if (force) {
                log.info("agent.llm.config-file is empty, skip local llm config loading");
            }
            clearLocalConfigIfPresent("agent.llm.config-file is empty");
            return;
        }
        Path path = Paths.get(file).toAbsolutePath().normalize();
        synchronized (reloadLock) {
            if (!Files.exists(path)) {
                if (force && this.localConfig == null) {
                    log.info("Local llm config file not found, skip: {}", path);
                }
                clearLocalConfigIfPresent("Local llm config file not found: " + path);
                return;
            }
            try {
                long currentModified = Files.getLastModifiedTime(path).toMillis();
                String normalizedPath = path.toString();
                boolean unchanged = normalizedPath.equals(loadedConfigPath) && currentModified == loadedConfigLastModified;
                if (!force && unchanged) {
                    return;
                }
                try (InputStream in = Files.newInputStream(path)) {
                    AgentLlmProperties parsed = objectMapper.readValue(in, AgentLlmProperties.class);
                    AgentLlmProperties sanitized = sanitize(parsed);
                    this.localConfig = sanitized;
                    this.loadedConfigPath = normalizedPath;
                    this.loadedConfigLastModified = currentModified;
                    // 计算从 endpoints 中收集的模型数量
                    int endpointModels = 0;
                    if (sanitized.getEndpoints() != null) {
                        for (AgentLlmProperties.Endpoint endpoint : sanitized.getEndpoints().values()) {
                            if (endpoint != null && endpoint.getModels() != null) {
                                endpointModels += endpoint.getModels().size();
                            }
                        }
                    }
                    log.info("Loaded local llm config from {} (endpoints={}, topLevelModels={}, endpointModels={}, modelMetadata={})",
                            path,
                            sanitized.getEndpoints().size(),
                            sanitized.getModels().size(),
                            endpointModels,
                            sanitized.getModelMetadata().size());
                }
            } catch (Exception e) {
                log.error("Failed to load local llm config from {}", path, e);
            }
        }
    }

    public Optional<AgentLlmProperties> current() {
        return Optional.ofNullable(localConfig);
    }

    private void clearLocalConfigIfPresent(String reason) {
        synchronized (reloadLock) {
            if (this.localConfig != null) {
                this.localConfig = null;
                this.loadedConfigPath = "";
                this.loadedConfigLastModified = Long.MIN_VALUE;
                log.warn("Local llm config cleared: {}", reason);
            }
        }
    }

    private AgentLlmProperties sanitize(AgentLlmProperties input) {
        AgentLlmProperties cfg = input == null ? new AgentLlmProperties() : input;
        if (cfg.getEndpoints() == null) {
            cfg.setEndpoints(null);
        } else {
            for (AgentLlmProperties.Endpoint endpoint : cfg.getEndpoints().values()) {
                if (endpoint == null) {
                    continue;
                }
                if (endpoint.getModels() == null) {
                    endpoint.setModels(null);
                    continue;
                }
                for (AgentLlmProperties.ModelMetadata metadata : endpoint.getModels().values()) {
                    if (metadata == null) {
                        continue;
                    }
                    if (metadata.getFeatures() == null) {
                        metadata.setFeatures(null);
                    }
                    if (metadata.getValidProviders() == null) {
                        metadata.setValidProviders(null);
                    }
                }
            }
        }
        if (cfg.getModels() == null) {
            cfg.setModels(null);
        }
        if (cfg.getModelMetadata() == null) {
            cfg.setModelMetadata(null);
        } else {
            for (AgentLlmProperties.ModelMetadata metadata : cfg.getModelMetadata().values()) {
                if (metadata != null && metadata.getFeatures() == null) {
                    metadata.setFeatures(null);
                }
                if (metadata != null && metadata.getValidProviders() == null) {
                    metadata.setValidProviders(null);
                }
            }
        }
        if (cfg.getPrompts() == null) {
            cfg.setPrompts(null);
        }
        if (cfg.getRuntime() == null) {
            cfg.setRuntime(null);
        }
        if (cfg.getRuntime().getResume() == null) {
            cfg.getRuntime().setResume(null);
        }
        if (cfg.getRuntime().getCache() == null) {
            cfg.getRuntime().setCache(null);
        }
        if (cfg.getRuntime().getPlanning() == null) {
            cfg.getRuntime().setPlanning(null);
        }
        if (cfg.getRuntime().getJudge() == null) {
            cfg.getRuntime().setJudge(null);
        }
        if (cfg.getRuntime().getJudge().getRoutes() == null) {
            cfg.getRuntime().getJudge().setRoutes(null);
        } else {
            for (AgentLlmProperties.JudgeRoute route : cfg.getRuntime().getJudge().getRoutes()) {
                if (route != null && route.getModels() == null) {
                    route.setModels(null);
                }
            }
        }
        if (cfg.getPrompts().getPythonRefineRequirements() == null) {
            cfg.getPrompts().setPythonRefineRequirements(null);
        }
        if (cfg.getPrompts().getDatasetFieldSpecs() == null) {
            cfg.getPrompts().setDatasetFieldSpecs(null);
        }
        return cfg;
    }
}

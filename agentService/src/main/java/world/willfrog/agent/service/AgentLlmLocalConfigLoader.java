package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @PostConstruct
    public void load() {
        String file = configFile == null ? "" : configFile.trim();
        if (file.isEmpty()) {
            log.info("agent.llm.config-file is empty, skip local llm config loading");
            return;
        }
        Path path = Paths.get(file);
        if (!Files.exists(path)) {
            log.info("Local llm config file not found, skip: {}", file);
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            AgentLlmProperties parsed = objectMapper.readValue(in, AgentLlmProperties.class);
            this.localConfig = sanitize(parsed);
            log.info("Loaded local llm config from {} (endpoints={}, models={})",
                    file,
                    this.localConfig.getEndpoints().size(),
                    this.localConfig.getModels().size());
        } catch (Exception e) {
            log.error("Failed to load local llm config from {}", file, e);
        }
    }

    public Optional<AgentLlmProperties> current() {
        return Optional.ofNullable(localConfig);
    }

    private AgentLlmProperties sanitize(AgentLlmProperties input) {
        AgentLlmProperties cfg = input == null ? new AgentLlmProperties() : input;
        if (cfg.getEndpoints() == null) {
            cfg.setEndpoints(null);
        }
        if (cfg.getModels() == null) {
            cfg.setModels(null);
        }
        return cfg;
    }
}


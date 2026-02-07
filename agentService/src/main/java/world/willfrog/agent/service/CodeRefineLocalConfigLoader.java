package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.CodeRefineProperties;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class CodeRefineLocalConfigLoader {

    private final ObjectMapper objectMapper;
    private final CodeRefineProperties properties;

    private volatile CodeRefineProperties localConfig;

    @PostConstruct
    public void load() {
        String file = properties.getConfigFile() == null ? "" : properties.getConfigFile().trim();
        if (file.isEmpty()) {
            log.info("agent.flow.code-refine.config-file is empty, skip local code refine config loading");
            return;
        }
        Path path = Paths.get(file);
        if (!Files.exists(path)) {
            log.info("Local code refine config file not found, skip: {}", file);
            return;
        }

        try (InputStream in = Files.newInputStream(path)) {
            CodeRefineProperties parsed = objectMapper.readValue(in, CodeRefineProperties.class);
            this.localConfig = sanitize(parsed);
            log.info("Loaded local code refine config from {} (maxAttempts={})", file, this.localConfig.getMaxAttempts());
        } catch (Exception e) {
            log.error("Failed to load local code refine config from {}", file, e);
        }
    }

    public Optional<CodeRefineProperties> current() {
        return Optional.ofNullable(localConfig);
    }

    private CodeRefineProperties sanitize(CodeRefineProperties input) {
        CodeRefineProperties cfg = input == null ? new CodeRefineProperties() : input;
        if (cfg.getMaxAttempts() <= 0) {
            cfg.setMaxAttempts(3);
        }
        return cfg;
    }
}

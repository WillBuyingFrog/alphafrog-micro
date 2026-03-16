package world.willfrog.externalinfo.ingestion.tushare;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

@Component
@Slf4j
public class RagFetchLocalConfigLoader {

    @Value("${alphafrog.rag.fetch.config-file:}")
    private String configFile;

    private final ObjectMapper objectMapper;
    private volatile RagFetchProperties localConfig;
    private final Object reloadLock = new Object();
    private long loadedConfigLastModified = -1;

    public RagFetchLocalConfigLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() {
        reloadIfNeeded(true);
    }

    @Scheduled(fixedDelayString = "${alphafrog.rag.fetch.config-refresh-interval-ms:10000}")
    public void refresh() {
        reloadIfNeeded(false);
    }

    public Optional<RagFetchProperties> current() {
        return Optional.ofNullable(localConfig);
    }

    private void reloadIfNeeded(boolean force) {
        if (configFile == null || configFile.isBlank()) {
            if (force) {
                log.info("alphafrog.rag.fetch.config-file is empty, skip RAG fetch config loading");
            }
            return;
        }
        Path path = Paths.get(configFile).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            if (force) {
                log.info("RAG fetch config file not found, skip: {}", path);
            }
            return;
        }
        try {
            long currentModified = Files.getLastModifiedTime(path).toMillis();
            if (!force && currentModified == loadedConfigLastModified) {
                return;
            }
            synchronized (reloadLock) {
                if (!force && currentModified == loadedConfigLastModified) {
                    return;
                }
                try (InputStream in = Files.newInputStream(path)) {
                    this.localConfig = objectMapper.readValue(in, RagFetchProperties.class);
                    this.loadedConfigLastModified = currentModified;
                    log.info("Loaded rag-fetch config from {}", path);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load rag-fetch config from {}: {}", configFile, e.getMessage());
        }
    }
}

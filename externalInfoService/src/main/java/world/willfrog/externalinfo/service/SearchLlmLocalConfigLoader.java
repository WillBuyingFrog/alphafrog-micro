package world.willfrog.externalinfo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.externalinfo.config.SearchLlmProperties;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class SearchLlmLocalConfigLoader {

    private final ObjectMapper objectMapper;

    @Value("${external-info.search-llm.config-file:}")
    private String configFile;

    private volatile SearchLlmProperties localConfig;
    private volatile String loadedConfigPath = "";
    private volatile long loadedConfigLastModified = Long.MIN_VALUE;
    private volatile Map<String, Long> loadedPromptFileModifiedTimes = new LinkedHashMap<>();
    private final Object reloadLock = new Object();

    private static final String FILE_PREFIX = "file:";
    private static final String FILE_PREFIX_ALT = "file://";
    private static final String FILE_PREFIX_AT = "@file:";

    @PostConstruct
    public void load() {
        reloadIfNeeded(true);
    }

    @Scheduled(fixedDelayString = "${external-info.search-llm.config-refresh-interval-ms:10000}")
    public void refresh() {
        reloadIfNeeded(false);
    }

    public Optional<SearchLlmProperties> current() {
        return Optional.ofNullable(localConfig);
    }

    private void reloadIfNeeded(boolean force) {
        String file = configFile == null ? "" : configFile.trim();
        if (file.isEmpty()) {
            if (force) {
                log.info("external-info.search-llm.config-file is empty, skip local search config loading");
            }
            clearLocalConfigIfPresent("external-info.search-llm.config-file is empty");
            return;
        }
        Path path = Paths.get(file).toAbsolutePath().normalize();
        synchronized (reloadLock) {
            if (!Files.exists(path)) {
                if (force && this.localConfig == null) {
                    log.info("Local search config file not found, skip: {}", path);
                }
                clearLocalConfigIfPresent("Local search config file not found: " + path);
                return;
            }
            try {
                long currentModified;
                try {
                    currentModified = Files.getLastModifiedTime(path).toMillis();
                } catch (Exception e) {
                    log.warn("Failed to read local search config metadata, will reload: {}", path, e);
                    currentModified = -1L;
                }
                String normalizedPath = path.toString();
                boolean unchanged = currentModified >= 0
                        && normalizedPath.equals(loadedConfigPath)
                        && currentModified == loadedConfigLastModified;
                if (!force && unchanged && !promptFilesChanged()) {
                    return;
                }
                try (InputStream in = Files.newInputStream(path)) {
                    JsonNode root = objectMapper.readTree(in);
                    failIfLegacyConfig(root, normalizedPath);
                    SearchLlmProperties parsed = objectMapper.treeToValue(root, SearchLlmProperties.class);
                    SearchLlmProperties sanitized = sanitize(parsed, normalizedPath);
                    Map<String, Long> promptFileTimes = resolvePromptFiles(sanitized, path.getParent());

                    int providerCount = sanitized.getProviders().size();
                    int profileCount = sanitized.getFeatures().getMarketNews().getProfiles().size();
                    this.localConfig = sanitized;
                    this.loadedConfigPath = normalizedPath;
                    this.loadedConfigLastModified = currentModified;
                    this.loadedPromptFileModifiedTimes = promptFileTimes;
                    log.info("Loaded local search config from {} (providers={}, marketNewsProfiles={})",
                            path,
                            providerCount,
                            profileCount);
                }
            } catch (Exception e) {
                log.error("Failed to load local search config from {}", path, e);
            }
        }
    }

    private void failIfLegacyConfig(JsonNode root, String path) {
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("search-llm config must be a JSON object: " + path);
        }
        if (root.has("defaultProvider") || root.has("marketNews")) {
            throw new IllegalStateException("Legacy fields detected (defaultProvider, marketNews). Migrate to features.marketNews.profiles schema. See README section 'Configure Search LLM (Market News 非兼容重构)': " + path);
        }
    }

    private boolean promptFilesChanged() {
        if (loadedPromptFileModifiedTimes == null || loadedPromptFileModifiedTimes.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Long> entry : loadedPromptFileModifiedTimes.entrySet()) {
            Path filePath = Paths.get(entry.getKey());
            if (!Files.exists(filePath)) {
                return true;
            }
            try {
                long lastModified = Files.getLastModifiedTime(filePath).toMillis();
                if (lastModified != entry.getValue()) {
                    return true;
                }
            } catch (Exception e) {
                return true;
            }
        }
        return false;
    }

    private void clearLocalConfigIfPresent(String reason) {
        synchronized (reloadLock) {
            if (this.localConfig != null) {
                this.localConfig = null;
                this.loadedConfigPath = "";
                this.loadedConfigLastModified = Long.MIN_VALUE;
                this.loadedPromptFileModifiedTimes = new LinkedHashMap<>();
                log.warn("Local search config cleared: {}", reason);
            }
        }
    }

    private SearchLlmProperties sanitize(SearchLlmProperties input, String configPath) {
        SearchLlmProperties cfg = input == null ? new SearchLlmProperties() : input;
        if (cfg.getProviders() == null) {
            cfg.setProviders(new LinkedHashMap<>());
        }
        if (cfg.getFeatures() == null) {
            cfg.setFeatures(new SearchLlmProperties.Features());
        }
        if (cfg.getFeatures().getMarketNews() == null) {
            cfg.getFeatures().setMarketNews(new SearchLlmProperties.MarketNewsFeature());
        }
        if (cfg.getPrompts() == null) {
            cfg.setPrompts(new SearchLlmProperties.Prompts());
        }

        SearchLlmProperties.MarketNewsFeature marketNews = cfg.getFeatures().getMarketNews();
        if (marketNews.getProfiles() == null || marketNews.getProfiles().isEmpty()) {
            throw new IllegalStateException("features.marketNews.profiles is required and must not be empty: " + configPath);
        }
        for (SearchLlmProperties.MarketNewsProfile profile : marketNews.getProfiles()) {
            if (profile == null) {
                throw new IllegalStateException("features.marketNews.profiles contains null profile");
            }
            if (!hasText(profile.getName())) {
                throw new IllegalStateException("features.marketNews.profiles[].name is required");
            }
            boolean hasQuery = hasText(profile.getQuery());
            boolean hasQueries = false;
            if (profile.getQueries() != null) {
                for (String q : profile.getQueries()) {
                    if (hasText(q)) {
                        hasQueries = true;
                        break;
                    }
                }
            }
            if (!hasQuery && !hasQueries) {
                throw new IllegalStateException("features.marketNews.profiles[" + profile.getName() + "] must set query or queries");
            }
        }
        return cfg;
    }

    private Map<String, Long> resolvePromptFiles(SearchLlmProperties cfg, Path baseDir) {
        Map<String, Long> fileTimes = new LinkedHashMap<>();
        if (cfg == null || cfg.getPrompts() == null || baseDir == null) {
            return fileTimes;
        }
        SearchLlmProperties.Prompts prompts = cfg.getPrompts();
        prompts.setMarketNewsQueryTemplate(resolvePromptText(prompts.getMarketNewsQueryTemplate(), baseDir, fileTimes));
        return fileTimes;
    }

    private String resolvePromptText(String value, Path baseDir, Map<String, Long> fileTimes) {
        if (!hasText(value)) {
            return value;
        }
        String raw = value.trim();
        String pathRef = stripFilePrefix(raw);
        if (pathRef == null) {
            return value;
        }
        Path filePath = resolveFilePath(pathRef, baseDir);
        if (filePath == null) {
            return "";
        }
        try {
            recordFileModifiedTime(filePath, fileTimes);
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to load prompt file: {}", filePath, e);
            return "";
        }
    }

    private void recordFileModifiedTime(Path filePath, Map<String, Long> fileTimes) {
        if (fileTimes == null || filePath == null) {
            return;
        }
        try {
            fileTimes.put(filePath.toString(), Files.getLastModifiedTime(filePath).toMillis());
        } catch (Exception e) {
            fileTimes.put(filePath.toString(), System.currentTimeMillis());
        }
    }

    private Path resolveFilePath(String pathRef, Path baseDir) {
        if (!hasText(pathRef)) {
            return null;
        }
        Path path = Paths.get(pathRef);
        if (!path.isAbsolute()) {
            path = baseDir.resolve(pathRef);
        }
        return path.toAbsolutePath().normalize();
    }

    private String stripFilePrefix(String value) {
        if (!hasText(value)) {
            return null;
        }
        String raw = value.trim();
        if (raw.startsWith(FILE_PREFIX_AT)) {
            return raw.substring(FILE_PREFIX_AT.length());
        }
        if (raw.startsWith(FILE_PREFIX_ALT)) {
            return raw.substring(FILE_PREFIX_ALT.length());
        }
        if (raw.startsWith(FILE_PREFIX)) {
            return raw.substring(FILE_PREFIX.length());
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

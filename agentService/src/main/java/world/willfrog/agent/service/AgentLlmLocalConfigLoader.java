package world.willfrog.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private volatile Map<String, Long> loadedPromptFileModifiedTimes = new LinkedHashMap<>();
    private final Object reloadLock = new Object();

    private static final String FILE_PREFIX = "file:";
    private static final String FILE_PREFIX_ALT = "file://";
    private static final String FILE_PREFIX_AT = "@file:";

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
                if (!force && unchanged && !promptFilesChanged()) {
                    return;
                }
                try (InputStream in = Files.newInputStream(path)) {
                    AgentLlmProperties parsed = objectMapper.readValue(in, AgentLlmProperties.class);
                    AgentLlmProperties sanitized = sanitize(parsed);
                    Map<String, Long> promptFileTimes = resolvePromptFiles(sanitized, path.getParent());
                    this.localConfig = sanitized;
                    this.loadedConfigPath = normalizedPath;
                    this.loadedConfigLastModified = currentModified;
                    this.loadedPromptFileModifiedTimes = promptFileTimes;
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
                log.warn("Local llm config cleared: {}", reason);
            }
        }
    }

    private Map<String, Long> resolvePromptFiles(AgentLlmProperties cfg, Path baseDir) {
        Map<String, Long> fileTimes = new LinkedHashMap<>();
        if (cfg == null || cfg.getPrompts() == null || baseDir == null) {
            return fileTimes;
        }
        AgentLlmProperties.Prompts prompts = cfg.getPrompts();
        prompts.setAgentRunSystemPrompt(resolvePromptText(prompts.getAgentRunSystemPrompt(), baseDir, fileTimes));
        prompts.setTodoPlannerSystemPromptTemplate(resolvePromptText(prompts.getTodoPlannerSystemPromptTemplate(), baseDir, fileTimes));
        prompts.setWorkflowFinalSystemPrompt(resolvePromptText(prompts.getWorkflowFinalSystemPrompt(), baseDir, fileTimes));
        prompts.setWorkflowTodoRecoverySystemPrompt(resolvePromptText(prompts.getWorkflowTodoRecoverySystemPrompt(), baseDir, fileTimes));
        prompts.setParallelPlannerSystemPromptTemplate(resolvePromptText(prompts.getParallelPlannerSystemPromptTemplate(), baseDir, fileTimes));
        prompts.setParallelFinalSystemPrompt(resolvePromptText(prompts.getParallelFinalSystemPrompt(), baseDir, fileTimes));
        prompts.setParallelPatchPlannerSystemPromptTemplate(resolvePromptText(prompts.getParallelPatchPlannerSystemPromptTemplate(), baseDir, fileTimes));
        prompts.setPlanJudgeSystemPromptTemplate(resolvePromptText(prompts.getPlanJudgeSystemPromptTemplate(), baseDir, fileTimes));
        prompts.setSemanticJudgeSystemPromptTemplate(resolvePromptText(prompts.getSemanticJudgeSystemPromptTemplate(), baseDir, fileTimes));
        prompts.setSubAgentPlannerSystemPromptTemplate(resolvePromptText(prompts.getSubAgentPlannerSystemPromptTemplate(), baseDir, fileTimes));
        prompts.setSubAgentSummarySystemPrompt(resolvePromptText(prompts.getSubAgentSummarySystemPrompt(), baseDir, fileTimes));
        prompts.setPythonRefineSystemPrompt(resolvePromptText(prompts.getPythonRefineSystemPrompt(), baseDir, fileTimes));
        prompts.setPythonRefineOutputInstruction(resolvePromptText(prompts.getPythonRefineOutputInstruction(), baseDir, fileTimes));
        prompts.setOrchestratorPlanningSystemPrompt(resolvePromptText(prompts.getOrchestratorPlanningSystemPrompt(), baseDir, fileTimes));
        prompts.setOrchestratorSummarySystemPrompt(resolvePromptText(prompts.getOrchestratorSummarySystemPrompt(), baseDir, fileTimes));

        if (hasText(prompts.getPythonRefineRequirementsFile())) {
            List<String> requirements = readPromptLines(prompts.getPythonRefineRequirementsFile(), baseDir, fileTimes);
            if (!requirements.isEmpty()) {
                prompts.setPythonRefineRequirements(requirements);
            }
        }

        if (hasText(prompts.getDatasetFieldSpecsFile())) {
            List<AgentLlmProperties.DatasetFieldSpec> specs = readDatasetFieldSpecs(prompts.getDatasetFieldSpecsFile(), baseDir, fileTimes);
            if (!specs.isEmpty()) {
                prompts.setDatasetFieldSpecs(specs);
            }
        }

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

    private List<String> readPromptLines(String fileRef, Path baseDir, Map<String, Long> fileTimes) {
        Path filePath = resolveFilePath(stripFilePrefixOrSelf(fileRef), baseDir);
        if (filePath == null) {
            return List.of();
        }
        try {
            recordFileModifiedTime(filePath, fileTimes);
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            for (String line : lines) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                out.add(trimmed);
            }
            return out;
        } catch (Exception e) {
            log.error("Failed to load prompt lines: {}", filePath, e);
            return List.of();
        }
    }

    private List<AgentLlmProperties.DatasetFieldSpec> readDatasetFieldSpecs(String fileRef,
                                                                            Path baseDir,
                                                                            Map<String, Long> fileTimes) {
        Path filePath = resolveFilePath(stripFilePrefixOrSelf(fileRef), baseDir);
        if (filePath == null) {
            return List.of();
        }
        try (InputStream in = Files.newInputStream(filePath)) {
            recordFileModifiedTime(filePath, fileTimes);
            return objectMapper.readValue(in, new TypeReference<List<AgentLlmProperties.DatasetFieldSpec>>() {});
        } catch (Exception e) {
            log.error("Failed to load dataset field specs: {}", filePath, e);
            return List.of();
        }
    }

    private void recordFileModifiedTime(Path filePath, Map<String, Long> fileTimes) {
        try {
            fileTimes.put(filePath.toString(), Files.getLastModifiedTime(filePath).toMillis());
        } catch (Exception e) {
            fileTimes.put(filePath.toString(), -1L);
        }
    }

    private Path resolveFilePath(String pathRef, Path baseDir) {
        if (!hasText(pathRef)) {
            return null;
        }
        Path path = Paths.get(pathRef.trim());
        if (!path.isAbsolute()) {
            path = baseDir.resolve(pathRef.trim());
        }
        return path.toAbsolutePath().normalize();
    }

    private String stripFilePrefix(String text) {
        if (!hasText(text)) {
            return null;
        }
        String raw = text.trim();
        if (raw.startsWith(FILE_PREFIX_ALT)) {
            return raw.substring(FILE_PREFIX_ALT.length()).trim();
        }
        if (raw.startsWith(FILE_PREFIX)) {
            return raw.substring(FILE_PREFIX.length()).trim();
        }
        if (raw.startsWith(FILE_PREFIX_AT)) {
            return raw.substring(FILE_PREFIX_AT.length()).trim();
        }
        return null;
    }

    private String stripFilePrefixOrSelf(String text) {
        String stripped = stripFilePrefix(text);
        return stripped == null ? text : stripped;
    }

    private boolean hasText(String text) {
        return text != null && !text.trim().isEmpty();
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
        if (cfg.getRuntime().getMultiTurn() == null) {
            cfg.getRuntime().setMultiTurn(null);
        }
        if (cfg.getRuntime().getMultiTurn().getCompression() == null) {
            cfg.getRuntime().getMultiTurn().setCompression(null);
        } else if (cfg.getRuntime().getMultiTurn().getCompression().getSummaryProviderOrder() == null) {
            cfg.getRuntime().getMultiTurn().getCompression().setSummaryProviderOrder(null);
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
        if (cfg.getObservability() == null) {
            cfg.setObservability(null);
        }
        if (cfg.getObservability() != null && cfg.getObservability().getOpenrouter() == null) {
            cfg.getObservability().setOpenrouter(null);
        }
        if (cfg.getObservability() != null && cfg.getObservability().getOpenrouter() != null 
                && cfg.getObservability().getOpenrouter().getCostEnrichment() == null) {
            cfg.getObservability().getOpenrouter().setCostEnrichment(null);
        }
        return cfg;
    }
}

package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.alphafrogmicro.common.config.ConfigLoadStateReporter;
import world.willfrog.alphafrogmicro.common.utils.PlaceholderResolver;

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

/**
 * 本地 LLM 配置热加载器 —— Nacos 推送的 {@code agent-llm.local.json} 通过此组件加载到内存。
 *
 * <h2>加载机制</h2>
 * <ol>
 *   <li>启动时通过 {@link #load()} 首次加载配置文件（{@code agent.llm.config-file} 指定的路径）</li>
 *   <li>每 10s（可配）通过 {@code @Scheduled} 轮询文件最后修改时间，
 *       有变化时重新解析 JSON → 替换内存中的 {@link AgentLlmProperties} 实例</li>
 *   <li>Nacos→文件→轮询→解析→原子替换，全程不需要重启服务</li>
 * </ol>
 *
 * <h2>{@code file:} 前缀解析</h2>
 * <p>配置中像 {@code "agentRunSystemPrompt": "file:prompts/agent/agent_run_system.txt"} 的字段，
 * 会在首次加载和每次重载时解析为文件内容并内联到配置对象中。
 * 引用的 prompt 文件也参与变更检测——prompt 文件改了同样触发重载。</p>
 *
 * <h2>面试常考点</h2>
 * <ul>
 *   <li>"配置怎么热更新？"→ Nacos 写文件 → 10s 轮询 → 检测 MD5 变化 → 原子替换</li>
 *   <li>"为什么不用 Redis pub/sub？"→ 文件轮询更简单，Nacos 本身负责把配置分发到文件，
 *       不需要额外引入消息通道</li>
 *   <li>"热加载失败怎么办？"→ 沿用上一次成功加载的配置，不影响正在运行的 agent</li>
 * </ul>
 *
 * @see AgentPromptService#currentPrompts() 消费方
 * @see AgentLlmProperties 配置结构定义
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentLlmLocalConfigLoader {

    private final ObjectMapper objectMapper;

    @Value("${agent.llm.config-file:}")
    private String configFile;

    @Value("${agent.llm.prompt-base-dir:}")
    private String promptBaseDir;

    @Value("${spring.application.name:agent-service}")
    private String serviceName;

    @Value("${spring.application.instance-id:${HOSTNAME:unknown}}")
    private String instanceId;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private volatile AgentLlmProperties localConfig;
    private volatile String loadedConfigPath = "";
    private volatile long loadedConfigLastModified = Long.MIN_VALUE;
    private volatile byte[] loadedConfigBytes = new byte[0];
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
                    reportState(loadedConfigBytes);
                    return;
                }
                try (InputStream in = Files.newInputStream(path)) {
                    byte[] bytes = in.readAllBytes();
                    AgentLlmProperties parsed = objectMapper.readValue(bytes, AgentLlmProperties.class);
                    PlaceholderResolver.resolve(parsed);
                    AgentLlmProperties sanitized = sanitize(parsed);
                    Map<String, Long> promptFileTimes = resolvePromptFiles(sanitized, resolvePromptBaseDir(path));
                    this.localConfig = sanitized;
                    this.loadedConfigPath = normalizedPath;
                    this.loadedConfigLastModified = currentModified;
                    this.loadedConfigBytes = bytes;
                    this.loadedPromptFileModifiedTimes = promptFileTimes;
                    reportState(bytes);
                    // 计算从 endpoints 中收集的模型数量
                    int endpointModels = 0;
                    if (sanitized.getEndpoints() != null) {
                        for (AgentLlmProperties.Endpoint endpoint : sanitized.getEndpoints().values()) {
                            if (endpoint != null && endpoint.getModels() != null) {
                                endpointModels += endpoint.getModels().size();
                            }
                        }
                    }
                    log.info("Loaded local llm config from {} (endpoints={}, topLevelModels={}, endpointModels={})",
                            path,
                            sanitized.getEndpoints().size(),
                            sanitized.getModels().size(),
                            endpointModels);
                }
            } catch (Exception e) {
                log.error("Failed to load local llm config from {}", path, e);
            }
        }
    }

    private void reportState(byte[] contentBytes) {
        ConfigLoadStateReporter.report(redisTemplate, serviceName, instanceId,
                "agent-llm.json", loadedConfigPath, contentBytes);
    }

    /**
     * 获取当前内存中已加载的 LLM 配置（热加载生效后的最新版本）。
     * 如果配置文件不存在或尚未加载成功，返回 {@code Optional.empty()}，
     * 调用方会 fallback 到 Spring Boot 静态配置（{@code application.yml}）。
     *
     * @return 当前生效的配置，可能为 empty
     */
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

    private Path resolvePromptBaseDir(Path configPath) {
        if (hasText(promptBaseDir)) {
            return Paths.get(promptBaseDir).toAbsolutePath().normalize();
        }
        return configPath == null ? null : configPath.getParent();
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
        prompts.setPlanJudgeRuntimeSystemPromptTemplate(resolvePromptText(prompts.getPlanJudgeRuntimeSystemPromptTemplate(), baseDir, fileTimes));
        prompts.setDagModeGuidancePrompt(resolvePromptText(prompts.getDagModeGuidancePrompt(), baseDir, fileTimes));
        prompts.setDagModeGuidancePromptFile(resolvePromptText(prompts.getDagModeGuidancePromptFile(), baseDir, fileTimes));
        prompts.setDagReactSystemPromptFile(resolvePromptText(prompts.getDagReactSystemPromptFile(), baseDir, fileTimes));
        
        // 加载两阶段 planning 的 prompt 文件（复用 resolvePromptText 处理 file: 前缀）
        prompts.setPlanningStrategyStage(resolvePromptText(prompts.getPlanningStrategyStageFile(), baseDir, fileTimes));
        prompts.setPlanningTodosStage(resolvePromptText(prompts.getPlanningTodosStageFile(), baseDir, fileTimes));

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
        if (cfg.getPrompts() == null) {
            cfg.setPrompts(null);
        }
        if (cfg.getRuntime() == null) {
            cfg.setRuntime(null);
        } else {
            sanitizeRuntime(cfg.getRuntime());
        }
        if (cfg.getRuntime() != null && cfg.getRuntime().getMultiTurn() == null) {
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
        if (cfg.getRuntime().getRunBudget() == null) {
            cfg.getRuntime().setRunBudget(null);
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
        if (cfg.getDebug() == null) {
            cfg.setDebug(null);
        }
        if (cfg.getOpenrouter() == null) {
            cfg.setOpenrouter(null);
        }
        return cfg;
    }

    private void sanitizeRuntime(AgentLlmProperties.Runtime runtime) {
        if (runtime.getExecution() != null && runtime.getExecution().getAdjFactorEnabled() == null) {
            runtime.getExecution().setAdjFactorEnabled(false);
        }
        if (runtime.getParallel() == null || runtime.getParallel().getToolWeightedLimit() == null) {
            return;
        }
        AgentLlmProperties.ToolWeightedLimit limit = runtime.getParallel().getToolWeightedLimit();
        if (limit.getTools() == null) {
            return;
        }
        for (AgentLlmProperties.ToolWeightEntry entry : limit.getTools().values()) {
            if (entry != null && entry.getRequiresAdjFactorEnabled() == null) {
                entry.setRequiresAdjFactorEnabled(false);
            }
        }
    }
}

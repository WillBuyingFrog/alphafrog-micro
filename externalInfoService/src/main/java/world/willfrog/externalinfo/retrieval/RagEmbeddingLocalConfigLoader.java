package world.willfrog.externalinfo.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.config.ConfigLoadStateReporter;
import world.willfrog.alphafrogmicro.common.utils.PlaceholderResolver;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * RAG Embedding 本地配置热加载器。
 *
 * <p>读取 {@code alphafrog.rag.embedding.config-file} 指向的 JSON 文件，
 * 定期检测文件修改时间，文件变更后自动重新加载，无需重启服务。</p>
 *
 * <p>配置文件示例：{@code externalInfoService/config/rag-embedding.local.example.json}</p>
 */
@Component
@Slf4j
public class RagEmbeddingLocalConfigLoader {

    @Value("${alphafrog.rag.embedding.config-file:}")
    private String configFile;

    @Value("${spring.application.name:external-info-service}")
    private String serviceName;

    @Value("${spring.application.instance-id:${HOSTNAME:unknown}}")
    private String instanceId;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper;
    private volatile RagEmbeddingProperties localConfig;
    private final Object reloadLock = new Object();
    private volatile String loadedConfigPath = "";
    private long loadedConfigLastModified = -1;
    private volatile byte[] loadedConfigBytes = new byte[0];

    public RagEmbeddingLocalConfigLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() {
        reloadIfNeeded(true);
    }

    @Scheduled(fixedDelayString = "${alphafrog.rag.embedding.config-refresh-interval-ms:10000}")
    public void refresh() {
        reloadIfNeeded(false);
    }

    /** 获取当前已加载的本地配置，未加载时返回 empty。 */
    public Optional<RagEmbeddingProperties> current() {
        return Optional.ofNullable(localConfig);
    }

    private void reloadIfNeeded(boolean force) {
        if (configFile == null || configFile.isBlank()) {
            if (force) {
                log.info("[RagEmbeddingLocalConfigLoader] config-file 未配置，跳过本地配置加载");
            }
            return;
        }
        Path path = Paths.get(configFile).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            if (force) {
                log.info("[RagEmbeddingLocalConfigLoader] 配置文件不存在，跳过: {}", path);
            }
            return;
        }
        try {
            long currentModified = Files.getLastModifiedTime(path).toMillis();
            if (!force && currentModified == loadedConfigLastModified) {
                reportState(loadedConfigBytes);
                return;
            }
            synchronized (reloadLock) {
                if (!force && currentModified == loadedConfigLastModified) {
                    reportState(loadedConfigBytes);
                    return;
                }
                try (InputStream in = Files.newInputStream(path)) {
                    byte[] bytes = in.readAllBytes();
                    RagEmbeddingProperties parsed = objectMapper.readValue(bytes, RagEmbeddingProperties.class);
                    PlaceholderResolver.resolve(parsed);
                    this.localConfig = parsed;
                    this.loadedConfigPath = path.toString();
                    this.loadedConfigLastModified = currentModified;
                    this.loadedConfigBytes = bytes;
                    reportState(bytes);
                    log.info("[RagEmbeddingLocalConfigLoader] 已加载 embedding 配置: {} model={} dimensions={}",
                            path,
                            localConfig.getModel().isBlank() ? "(默认)" : localConfig.getModel(),
                            localConfig.getDimensions() == 0 ? "(默认)" : localConfig.getDimensions());
                }
            }
        } catch (Exception e) {
            log.error("[RagEmbeddingLocalConfigLoader] 加载失败: {} - {}", configFile, e.getMessage());
        }
    }

    private void reportState(byte[] contentBytes) {
        ConfigLoadStateReporter.report(redisTemplate, serviceName, instanceId,
                "rag-embedding.json", loadedConfigPath, contentBytes);
    }
}

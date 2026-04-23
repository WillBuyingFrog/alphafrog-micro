package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import world.willfrog.agent.config.CodeRefineProperties;
import world.willfrog.alphafrogmicro.common.utils.PlaceholderResolver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Code Refine 本地配置热加载器。
 *
 * <p>支持文件轮询热加载、Nacos 推送后的自动感知、配置状态 Redis 上报。</p>
 */
@Component
@Slf4j
public class CodeRefineLocalConfigLoader {

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final CodeRefineProperties properties;

    @Value("${spring.application.name:agent-service}")
    private String serviceName;

    private volatile CodeRefineProperties localConfig;
    private volatile String loadedConfigPath = "";
    private volatile long loadedConfigLastModified = -1;

    public CodeRefineLocalConfigLoader(ObjectMapper objectMapper,
                                        StringRedisTemplate redisTemplate,
                                        CodeRefineProperties properties) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @PostConstruct
    public void load() {
        reloadIfNeeded(true);
    }

    @Scheduled(fixedDelayString = "${agent.flow.code-refine.config-refresh-interval-ms:10000}")
    public void refresh() {
        reloadIfNeeded(false);
    }

    private void reloadIfNeeded(boolean force) {
        String file = properties.getConfigFile() == null ? "" : properties.getConfigFile().trim();
        if (file.isEmpty()) {
            if (force) {
                log.info("agent.flow.code-refine.config-file is empty, skip local code refine config loading");
            }
            return;
        }
        Path path = Paths.get(file).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            if (force) {
                log.info("Local code refine config file not found, skip: {}", path);
            }
            return;
        }

        try {
            long currentModified = Files.getLastModifiedTime(path).toMillis();
            String normalizedPath = path.toString();
            if (!force && normalizedPath.equals(loadedConfigPath) && currentModified == loadedConfigLastModified) {
                return;
            }

            try (InputStream in = Files.newInputStream(path)) {
                byte[] bytes = in.readAllBytes();
                CodeRefineProperties parsed = objectMapper.readValue(bytes, CodeRefineProperties.class);

                // 解析 ${ENV_VAR} 占位符
                PlaceholderResolver.resolve(parsed);

                this.localConfig = sanitize(parsed);
                this.loadedConfigPath = normalizedPath;
                this.loadedConfigLastModified = currentModified;

                log.info("Loaded local code refine config from {} (maxAttempts={})",
                        path, this.localConfig.getMaxAttempts());

                // 上报 Redis 状态
                reportState(bytes);
            }
        } catch (Exception e) {
            log.error("Failed to load local code refine config from {}", path, e);
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

    /**
     * 上报配置加载状态到 Redis，供 admin 查询副本生效情况。
     */
    private void reportState(byte[] contentBytes) {
        if (redisTemplate == null) {
            return;
        }
        try {
            String md5 = DigestUtils.md5DigestAsHex(contentBytes);
            String dataId = "code-refine.json";
            String key = String.format("config:state:%s:%s", serviceName, dataId);
            String value = String.format("{\"md5\":\"%s\",\"loadedAt\":\"%s\"}",
                    md5, Instant.now().toString());
            redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(30));
        } catch (Exception e) {
            log.warn("[CodeRefineLocalConfigLoader] Redis 状态上报失败", e);
        }
    }
}

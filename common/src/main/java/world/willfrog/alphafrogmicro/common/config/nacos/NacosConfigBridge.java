package world.willfrog.alphafrogmicro.common.config.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * Nacos 配置桥接器。
 *
 * <p>订阅 Nacos Config 的指定 dataId，收到推送后三段式写本地文件，
 * 让各微服务现有的 *LocalConfigLoader 通过文件轮询自动热加载。</p>
 */
@Slf4j
@Component
public class NacosConfigBridge {

    @Value("${alphafrog.config.nacos.server-addr:127.0.0.1:8848}")
    private String serverAddr;

    @Value("${alphafrog.config.nacos.namespace:}")
    private String namespace;

    @Value("${alphafrog.config.nacos.data-id:}")
    private String dataId;

    @Value("${alphafrog.config.nacos.group:alphafrog-config}")
    private String group;

    @Value("${alphafrog.config.nacos.enabled:false}")
    private boolean enabled;

    @Value("${agent.flow.code-refine.config-file:}")
    private String configFilePath;

    private final ObjectMapper objectMapper;
    private ConfigService configService;
    private String listenerId;

    public NacosConfigBridge(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("[NacosConfigBridge] 未启用，跳过初始化");
            return;
        }
        if (dataId == null || dataId.isBlank()) {
            log.warn("[NacosConfigBridge] data-id 未配置，跳过初始化");
            return;
        }
        if (configFilePath == null || configFilePath.isBlank()) {
            log.warn("[NacosConfigBridge] config-file 未配置，跳过初始化");
            return;
        }

        try {
            Properties properties = new Properties();
            properties.put("serverAddr", serverAddr);
            if (namespace != null && !namespace.isBlank()) {
                properties.put("namespace", namespace);
            }
            this.configService = NacosFactory.createConfigService(properties);

            // 启动时先拉取一次初始配置
            String initialConfig = configService.getConfig(dataId, group, 5000);
            if (initialConfig != null && !initialConfig.isBlank()) {
                writeConfigToFile(initialConfig);
            }

            // 注册推送监听
            configService.addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String config) {
                    log.info("[NacosConfigBridge] 收到配置推送 dataId={}", dataId);
                    writeConfigToFile(config);
                }
            });

            log.info("[NacosConfigBridge] 已订阅 Nacos 配置 server={} dataId={} group={} filePath={}",
                    serverAddr, dataId, group, configFilePath);
        } catch (NacosException e) {
            log.error("[NacosConfigBridge] Nacos 初始化失败", e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (configService != null) {
            try {
                configService.shutDown();
            } catch (NacosException e) {
                log.warn("[NacosConfigBridge] 关闭 Nacos 客户端异常", e);
            }
        }
    }

    /**
     * 三段式写文件：tmp → fsync → atomic move → 读回校验
     */
    private void writeConfigToFile(String configContent) {
        Path targetPath = Paths.get(configFilePath).toAbsolutePath().normalize();
        Path tmpPath = Paths.get(configFilePath + ".tmp");
        Path backupPath = Paths.get(configFilePath + ".backup." + System.currentTimeMillis());

        try {
            // 0. 若目标文件存在，先备份
            if (Files.exists(targetPath)) {
                Files.copy(targetPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // 1. 写临时文件
            Files.writeString(tmpPath, configContent, StandardCharsets.UTF_8);

            // 2. fsync（确保数据落盘）
            try (FileChannel channel = FileChannel.open(tmpPath,
                    StandardOpenOption.WRITE)) {
                channel.force(true);
            }

            // 3. 原子移动到目标路径
            try {
                Files.move(tmpPath, targetPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                log.warn("[NacosConfigBridge] 原子移动不支持，回退到普通移动: {}", targetPath);
                Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // 4. 读回校验
            objectMapper.readTree(targetPath.toFile());

            log.info("[NacosConfigBridge] 配置已写入文件: {}", targetPath);
        } catch (IOException e) {
            log.error("[NacosConfigBridge] 写文件失败: {}", targetPath, e);
            // 尝试用备份还原
            if (Files.exists(backupPath)) {
                try {
                    Files.move(backupPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    log.info("[NacosConfigBridge] 已用备份还原: {}", targetPath);
                } catch (IOException restoreEx) {
                    log.error("[NacosConfigBridge] 备份还原也失败: {}", targetPath, restoreEx);
                }
            }
        } finally {
            // 清理临时文件
            try {
                Files.deleteIfExists(tmpPath);
            } catch (IOException ignored) {
            }
        }
    }
}

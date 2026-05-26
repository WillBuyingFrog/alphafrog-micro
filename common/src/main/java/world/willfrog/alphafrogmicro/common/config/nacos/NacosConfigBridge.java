package world.willfrog.alphafrogmicro.common.config.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final Environment environment;
    private ConfigService configService;
    private final List<Subscription> activeSubscriptions = new ArrayList<>();
    private final Map<String, String> lastWrittenContentBySubscription = new LinkedHashMap<>();

    @Autowired
    public NacosConfigBridge(ObjectProvider<ObjectMapper> objectMapperProvider, Environment environment) {
        this(objectMapperProvider.getIfAvailable(ObjectMapper::new), environment);
    }

    public NacosConfigBridge(ObjectMapper objectMapper, Environment environment) {
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("[NacosConfigBridge] 未启用，跳过初始化");
            return;
        }
        List<Subscription> subscriptions = resolveSubscriptions();
        if (subscriptions.isEmpty()) {
            log.warn("[NacosConfigBridge] 未配置有效订阅，跳过初始化");
            return;
        }

        try {
            Properties properties = new Properties();
            properties.put("serverAddr", serverAddr);
            if (namespace != null && !namespace.isBlank()) {
                properties.put("namespace", namespace);
            }
            this.configService = NacosFactory.createConfigService(properties);

            for (Subscription subscription : subscriptions) {
                subscribe(subscription);
            }
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

    private List<Subscription> resolveSubscriptions() {
        List<Subscription> subscriptions = Binder.get(environment)
                .bind("alphafrog.config.nacos.subscriptions",
                        org.springframework.boot.context.properties.bind.Bindable.listOf(Subscription.class))
                .orElseGet(ArrayList::new);
        List<Subscription> valid = new ArrayList<>();
        for (Subscription subscription : subscriptions) {
            if (subscription == null || isBlank(subscription.getDataId()) || isBlank(subscription.getTargetFile())) {
                continue;
            }
            if (isBlank(subscription.getGroup())) {
                subscription.setGroup(group);
            }
            valid.add(subscription);
        }
        if (valid.isEmpty() && !isBlank(dataId) && !isBlank(configFilePath)) {
            Subscription legacy = new Subscription();
            legacy.setDataId(dataId);
            legacy.setGroup(group);
            legacy.setTargetFile(configFilePath);
            valid.add(legacy);
        }
        return valid;
    }

    private void subscribe(Subscription subscription) throws NacosException {
        String subscriptionGroup = isBlank(subscription.getGroup()) ? group : subscription.getGroup();
        clearNacosLocalSnapshot(subscription.getDataId(), subscriptionGroup);
        String initialConfig = configService.getConfig(subscription.getDataId(), subscriptionGroup, 5000);
        if (initialConfig != null && !initialConfig.isBlank()) {
            writeConfigToFileIfChanged(subscription, initialConfig, "initial-load");
        }

        configService.addListener(subscription.getDataId(), subscriptionGroup, new Listener() {
            @Override
            public Executor getExecutor() {
                return null;
            }

            @Override
            public void receiveConfigInfo(String config) {
                log.info("[NacosConfigBridge] 收到配置推送 dataId={}", subscription.getDataId());
                if (config == null || config.isBlank()) {
                    log.warn("[NacosConfigBridge] 忽略空白配置推送 dataId={}", subscription.getDataId());
                    return;
                }
                writeConfigToFileIfChanged(subscription, config, "listener");
            }
        });
        synchronized (activeSubscriptions) {
            activeSubscriptions.add(subscription);
        }
        log.info("[NacosConfigBridge] 已订阅 Nacos 配置 server={} dataId={} group={} filePath={}",
                serverAddr, subscription.getDataId(), subscriptionGroup, subscription.getTargetFile());
    }

    /**
     * Periodic pull refresh for missed Nacos listener events or stale long-poll connections.
     */
    @Scheduled(fixedDelayString = "${alphafrog.config.nacos.refresh-interval-ms:30000}")
    public void refreshSubscriptions() {
        if (!enabled || configService == null || activeSubscriptions.isEmpty()) {
            return;
        }
        List<Subscription> subscriptionsSnapshot;
        synchronized (activeSubscriptions) {
            subscriptionsSnapshot = List.copyOf(activeSubscriptions);
        }
        for (Subscription subscription : subscriptionsSnapshot) {
            String subscriptionGroup = isBlank(subscription.getGroup()) ? group : subscription.getGroup();
            try {
                String latest = configService.getConfig(subscription.getDataId(), subscriptionGroup, 5000);
                if (latest == null || latest.isBlank()) {
                    log.warn("[NacosConfigBridge] 定时刷新忽略空白配置 dataId={}", subscription.getDataId());
                    continue;
                }
                writeConfigToFileIfChanged(subscription, latest, "periodic-refresh");
            } catch (Exception e) {
                log.warn("[NacosConfigBridge] 定时刷新失败 dataId={} group={}",
                        subscription.getDataId(), subscriptionGroup, e);
            }
        }
    }

    private void writeConfigToFileIfChanged(Subscription subscription, String configContent, String source) {
        String key = subscriptionKey(subscription);
        synchronized (lastWrittenContentBySubscription) {
            String lastWritten = lastWrittenContentBySubscription.get(key);
            if (configContent.equals(lastWritten) && fileContentEquals(subscription, configContent)) {
                return;
            }
            writeConfigToFile(subscription, configContent);
            if (fileContentEquals(subscription, configContent)) {
                lastWrittenContentBySubscription.put(key, configContent);
                log.info("[NacosConfigBridge] 配置同步完成 dataId={} source={}",
                        subscription.getDataId(), source);
            }
        }
    }

    private boolean fileContentEquals(Subscription subscription, String configContent) {
        try {
            Path targetPath = Paths.get(subscription.getTargetFile()).toAbsolutePath().normalize();
            return Files.exists(targetPath)
                    && configContent.equals(Files.readString(targetPath, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
    }

    private String subscriptionKey(Subscription subscription) {
        String subscriptionGroup = isBlank(subscription.getGroup()) ? group : subscription.getGroup();
        return subscription.getDataId() + "\n" + subscriptionGroup + "\n" + subscription.getTargetFile();
    }

    private void clearNacosLocalSnapshot(String dataId, String subscriptionGroup) {
        if (isBlank(dataId)) {
            return;
        }
        String effectiveServerAddr = isBlank(serverAddr) ? "127.0.0.1:8848" : serverAddr;
        String fixedAddr = "fixed-" + effectiveServerAddr.replace(":", "_");
        String namespaceDir = isBlank(namespace) ? "nacos" : namespace;
        Path snapshotFile = Paths.get(System.getProperty("user.home"),
                "nacos", "config", fixedAddr, namespaceDir, "snapshot", subscriptionGroup, dataId);
        try {
            if (Files.deleteIfExists(snapshotFile)) {
                log.info("[NacosConfigBridge] 已清除本地快照: {}", snapshotFile);
            }
        } catch (IOException e) {
            log.warn("[NacosConfigBridge] 清除本地快照失败: {}", snapshotFile, e);
        }
    }

    /**
     * 三段式写文件：tmp → fsync → JSON 校验 → atomic move → 读回校验
     */
    private void writeConfigToFile(Subscription subscription, String configContent) {
        Path targetPath = Paths.get(subscription.getTargetFile()).toAbsolutePath().normalize();
        Path tmpPath = Paths.get(subscription.getTargetFile() + ".tmp");
        Path backupPath = Paths.get(subscription.getTargetFile() + ".backup." + System.currentTimeMillis());
        boolean movedToTarget = false;

        try {
            Files.createDirectories(targetPath.getParent());
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

            // 3. 移动前先校验临时文件，避免无效 JSON 覆盖可用配置。
            objectMapper.readTree(tmpPath.toFile());

            // 4. 原子移动到目标路径
            try {
                Files.move(tmpPath, targetPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                movedToTarget = true;
            } catch (AtomicMoveNotSupportedException e) {
                log.warn("[NacosConfigBridge] 原子移动不支持，回退到普通移动: {}", targetPath);
                Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                movedToTarget = true;
            }

            // 5. 读回校验
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
            } else if (movedToTarget) {
                try {
                    Files.deleteIfExists(targetPath);
                    log.info("[NacosConfigBridge] 已删除校验失败的新配置文件: {}", targetPath);
                } catch (IOException deleteEx) {
                    log.error("[NacosConfigBridge] 删除校验失败的新配置文件也失败: {}", targetPath, deleteEx);
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static class Subscription {
        private String dataId;
        private String group;
        private String targetFile;

        public String getDataId() {
            return dataId;
        }

        public void setDataId(String dataId) {
            this.dataId = dataId;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getTargetFile() {
            return targetFile;
        }

        public void setTargetFile(String targetFile) {
            this.targetFile = targetFile;
        }
    }
}

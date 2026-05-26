package world.willfrog.alphafrogmicro.domestic.fetch.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.config.ConfigLoadStateReporter;
import world.willfrog.alphafrogmicro.common.utils.PlaceholderResolver;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 抓取任务配置管理器
 * 支持热更新：默认每 10 秒检查配置文件是否有变化
 */
@Component
@Slf4j
public class FetchJobsConfig {

    private static final String CONFIG_FILE_NAME = "fetch-jobs.json";
    
    @Value("${af.fetch.jobs.config-file:}")
    private String configFilePath;
    
    @Value("${af.fetch.jobs.config-refresh-interval-ms:10000}")
    private long refreshIntervalMs;

    @Value("${spring.application.name:domestic-fetch-service}")
    private String serviceName;

    @Value("${spring.application.instance-id:${HOSTNAME:unknown}}")
    private String instanceId;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;
    
    // 配置数据
    private volatile JSONObject config;
    
    // 已加载的文件路径和最后修改时间
    private volatile String loadedConfigPath = "";
    private volatile long loadedConfigLastModified = Long.MIN_VALUE;
    private volatile byte[] loadedConfigBytes = new byte[0];
    
    private final Object reloadLock = new Object();
    
    @PostConstruct
    public void init() {
        reloadIfNeeded(true);
    }
    
    /**
     * 定时检查配置文件是否有变化，默认 10 秒执行一次。
     */
    @Scheduled(fixedDelayString = "${af.fetch.jobs.config-refresh-interval-ms:10000}")
    public void refresh() {
        reloadIfNeeded(false);
    }
    
    /**
     * 检查并重新加载配置
     * @param force 是否强制重新加载
     */
    private void reloadIfNeeded(boolean force) {
        String configPath = resolveConfigPath();
        
        if (configPath == null || configPath.isBlank()) {
            if (force && config == null) {
                log.info("Fetch jobs config path not set, using default configuration");
                config = createDefaultConfig();
            }
            return;
        }
        
        Path path = Paths.get(configPath).toAbsolutePath().normalize();
        
        synchronized (reloadLock) {
            if (!Files.exists(path)) {
                if (force && config == null) {
                    log.warn("Fetch jobs config file not found: {}, using default configuration", path);
                    config = createDefaultConfig();
                }
                return;
            }
            
            try {
                long currentModified = Files.getLastModifiedTime(path).toMillis();
                String normalizedPath = path.toString();
                
                boolean unchanged = normalizedPath.equals(loadedConfigPath) 
                        && currentModified == loadedConfigLastModified;
                
                if (!force && unchanged) {
                    reportState(loadedConfigBytes);
                    return;
                }
                
                // 加载新配置
                try (InputStream is = Files.newInputStream(path)) {
                    byte[] bytes = is.readAllBytes();
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    JSONObject newConfig = JSON.parseObject(content);
                    
                    // 解析环境变量占位符
                    PlaceholderResolver.resolveJsonObject(newConfig);

                    this.config = newConfig;
                    this.loadedConfigPath = normalizedPath;
                    this.loadedConfigLastModified = currentModified;
                    this.loadedConfigBytes = bytes;
                    reportState(bytes);
                    
                    log.info("Loaded fetch jobs config from {} (scheduledJobs={})", 
                            path, 
                            newConfig.getJSONObject("scheduledJobs") != null ? 
                                    newConfig.getJSONObject("scheduledJobs").size() : 0);
                }
                
            } catch (Exception e) {
                log.error("Failed to load fetch jobs config from {}, keeping current config", path, e);
                if (config == null) {
                    config = createDefaultConfig();
                }
            }
        }
    }

    private void reportState(byte[] contentBytes) {
        ConfigLoadStateReporter.report(redisTemplate, serviceName, instanceId,
                "fetch-jobs.json", loadedConfigPath, contentBytes);
    }
    
    /**
     * 解析配置文件路径
     */
    private String resolveConfigPath() {
        // 1. 如果配置了具体路径，优先使用
        if (configFilePath != null && !configFilePath.trim().isEmpty()) {
            return configFilePath.trim();
        }
        
        // 2. 尝试当前目录的 config 文件夹
        File localConfig = new File("config/" + CONFIG_FILE_NAME);
        if (localConfig.exists()) {
            return localConfig.getAbsolutePath();
        }
        
        // 3. 尝试类路径下的配置文件（转为绝对路径）
        try {
            java.net.URL resource = getClass().getClassLoader().getResource(CONFIG_FILE_NAME);
            if (resource != null) {
                return Paths.get(resource.toURI()).toAbsolutePath().toString();
            }
        } catch (Exception e) {
            // 忽略
        }
        
        return null;
    }
    
    /**
     * 创建默认配置（全部启用）
     */
    private JSONObject createDefaultConfig() {
        JSONObject defaultConfig = new JSONObject();
        
        JSONObject scheduledJobs = new JSONObject();
        
        JSONObject ragAnn = new JSONObject();
        ragAnn.put("enabled", true);
        ragAnn.put("cron", "0 0 6 * * *");
        scheduledJobs.put("ragAnnouncementFetch", ragAnn);
        
        JSONObject ragReport = new JSONObject();
        ragReport.put("enabled", true);
        ragReport.put("cron", "0 30 6 * * *");
        scheduledJobs.put("ragResearchReportFetch", ragReport);
        
        defaultConfig.put("scheduledJobs", scheduledJobs);
        
        JSONObject fetch = new JSONObject();
        fetch.put("concurrency", 3);
        fetch.put("timeoutSeconds", 1800);
        defaultConfig.put("fetch", fetch);
        
        return defaultConfig;
    }
    
    /**
     * 检查定时任务是否启用
     * @param jobName 任务名称，如 ragAnnouncementFetch, ragResearchReportFetch
     * @return true 如果任务启用
     */
    public boolean isJobEnabled(String jobName) {
        if (config == null) {
            return true; // 默认启用
        }
        
        JSONObject scheduledJobs = config.getJSONObject("scheduledJobs");
        if (scheduledJobs == null) {
            return true;
        }
        
        JSONObject jobConfig = scheduledJobs.getJSONObject(jobName);
        if (jobConfig == null) {
            return true; // 未配置的任务默认启用
        }
        
        Boolean enabled = jobConfig.getBoolean("enabled");
        return enabled != null ? enabled : true;
    }
    
    /**
     * 获取任务的 cron 表达式
     * @param jobName 任务名称
     * @param defaultCron 默认 cron 表达式
     * @return cron 表达式
     */
    public String getJobCron(String jobName, String defaultCron) {
        if (config == null) {
            return defaultCron;
        }
        
        JSONObject scheduledJobs = config.getJSONObject("scheduledJobs");
        if (scheduledJobs == null) {
            return defaultCron;
        }
        
        JSONObject jobConfig = scheduledJobs.getJSONObject(jobName);
        if (jobConfig == null) {
            return defaultCron;
        }
        
        String cron = jobConfig.getString("cron");
        return cron != null && !cron.isBlank() ? cron : defaultCron;
    }
    
    /**
     * 获取抓取并发数
     */
    public int getConcurrency() {
        if (config == null) {
            return 3;
        }
        JSONObject fetch = config.getJSONObject("fetch");
        if (fetch == null) {
            return 3;
        }
        Integer concurrency = fetch.getInteger("concurrency");
        return concurrency != null ? concurrency : 3;
    }
    
    /**
     * 获取抓取超时时间（秒）
     */
    public int getTimeoutSeconds() {
        if (config == null) {
            return 1800;
        }
        JSONObject fetch = config.getJSONObject("fetch");
        if (fetch == null) {
            return 1800;
        }
        Integer timeout = fetch.getInteger("timeoutSeconds");
        return timeout != null ? timeout : 1800;
    }
    
    /**
     * 手动触发重新加载（外部调用）
     */
    public void reload() {
        log.info("Manual reload triggered for fetch jobs config");
        reloadIfNeeded(true);
    }
    
    /**
     * 获取当前加载的配置文件路径
     */
    public String getLoadedConfigPath() {
        return loadedConfigPath;
    }
}

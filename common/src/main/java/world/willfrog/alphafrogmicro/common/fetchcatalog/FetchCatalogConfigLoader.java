package world.willfrog.alphafrogmicro.common.fetchcatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin Fetch 任务 Catalog JSON 配置加载器。
 * 启动时从 classpath 加载 catalog-index.json 及所有数据类型配置，并提供查询能力。
 */
@Component
@Slf4j
public class FetchCatalogConfigLoader {

    private static final String CATALOG_INDEX_PATH = "fetch-catalog/catalog-index.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, FetchDataTypeConfig> configMap = new HashMap<>();

    @PostConstruct
    public void load() {
        log.info("Loading fetch catalog configurations from classpath...");
        try {
            FetchCatalogIndex index = readJson(CATALOG_INDEX_PATH, FetchCatalogIndex.class);
            if (index == null || index.getDataTypes() == null) {
                log.warn("Catalog index is empty or missing dataTypes");
                return;
            }

            for (FetchCatalogIndex.DataTypeEntry entry : index.getDataTypes()) {
                String name = entry.getName();
                String filePath = entry.getFile();
                try {
                    FetchDataTypeConfig config = readJson(filePath, FetchDataTypeConfig.class);
                    if (config != null) {
                        configMap.put(name, config);
                        log.info("Loaded fetch catalog for dataType={}, taskVariants={}, taskSetVariants={}",
                                name,
                                config.getTaskVariants() != null ? config.getTaskVariants().size() : 0,
                                config.getTaskSetVariants() != null ? config.getTaskSetVariants().size() : 0);
                    } else {
                        log.warn("Failed to load fetch catalog config for {}: file {} returned null", name, filePath);
                    }
                } catch (Exception e) {
                    log.error("Failed to load fetch catalog config for {} from {}", name, filePath, e);
                }
            }

            log.info("Fetch catalog loading completed. Total data types loaded: {}", configMap.size());
            log.info("Fetch catalog configs loaded successfully: {}", configMap.keySet());
        } catch (Exception e) {
            log.error("Failed to load fetch catalog index from {}", CATALOG_INDEX_PATH, e);
        }
    }

    /**
     * 获取所有已加载的数据类型配置（只读视图）。
     */
    public Map<String, FetchDataTypeConfig> getAllConfigs() {
        return Collections.unmodifiableMap(configMap);
    }

    /**
     * 获取指定数据类型的配置。
     */
    public FetchDataTypeConfig findConfig(String dataType) {
        return configMap.get(dataType);
    }

    /**
     * 获取所有支持的数据类型名称列表。
     */
    public List<String> listAllDataTypes() {
        return List.copyOf(configMap.keySet());
    }

    /**
     * 查找指定数据类型和 subType 的 task variant 配置。
     */
    public TaskVariantConfig findTaskVariant(String dataType, int subType) {
        FetchDataTypeConfig config = configMap.get(dataType);
        if (config == null) return null;
        return config.findTaskVariant(subType);
    }

    /**
     * 查找指定数据类型和 subType 的 task set variant 配置。
     */
    public TaskSetVariantConfig findTaskSetVariant(String dataType, int subType) {
        FetchDataTypeConfig config = configMap.get(dataType);
        if (config == null) return null;
        return config.findTaskSetVariant(subType);
    }

    private <T> T readJson(String path, Class<T> clazz) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream is = resource.getInputStream()) {
            return objectMapper.readValue(is, clazz);
        }
    }
}

package world.willfrog.alphafrogmicro.common.fetchcatalog;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Admin Fetch 任务 Catalog JSON 配置加载器。
 * <p>
 * 在 Spring 容器启动时通过 {@code @PostConstruct} 自动从 classpath 加载
 * {@code fetch-catalog/catalog-index.json} 索引文件及其引用的所有数据类型配置。
 * 加载完成后对外提供按 dataType / subType 查询配置的能力。
 * </p>
 * <p>
 * <b>Fail-Fast 策略</b>：如果索引文件加载失败或最终没有任何有效配置被加载，
 * 将抛出运行时异常阻止应用启动，避免下游服务在运行时因缺少配置而产生难以排查的 NPE。
 * </p>
 *
 * @see FetchCatalogIndex
 * @see FetchDataTypeConfig
 */
@Component
@Slf4j
public class FetchCatalogConfigLoader {

    private static final String CATALOG_INDEX_PATH = "fetch-catalog/catalog-index.json";

    /** 显式关闭未知属性报错，提高 JSON 配置的前向兼容性 */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 使用 LinkedHashMap 保持加载顺序，便于前端列表展示时顺序可预期 */
    private final Map<String, FetchDataTypeConfig> configMap = new LinkedHashMap<>();

    /**
     * 应用启动时自动加载所有 Fetch Catalog 配置。
     * <p>
     * 加载流程：<br>
     * 1. 读取 catalog-index.json 索引文件<br>
     * 2. 遍历索引中声明的 dataType 条目，逐个加载对应 JSON 配置<br>
     * 3. 对每个 dataType 做基本校验（taskVariants 的 subType 唯一性等）<br>
     * 4. 如果最终 configMap 为空，抛出异常阻止启动
     * </p>
     *
     * @throws RuntimeException 当索引文件不可读或 configMap 为空时
     */
    @PostConstruct
    public void load() {
        log.info("Loading fetch catalog configurations from classpath...");
        try {
            FetchCatalogIndex index = readJson(CATALOG_INDEX_PATH, FetchCatalogIndex.class);
            if (index == null || index.getDataTypes() == null) {
                throw new IllegalStateException("Catalog index is empty or missing dataTypes");
            }
            if (index.getVersion() != null) {
                log.info("Loading fetch catalog index version: {}", index.getVersion());
            }

            int failedCount = 0;
            for (FetchCatalogIndex.DataTypeEntry entry : index.getDataTypes()) {
                String name = entry.getName();
                String filePath = entry.getFile();
                try {
                    FetchDataTypeConfig config = readJson(filePath, FetchDataTypeConfig.class);
                    if (config != null) {
                        validateConfig(name, config);
                        configMap.put(name, config);
                        log.info("Loaded fetch catalog for dataType={}, taskVariants={}, taskSetVariants={}",
                                name,
                                config.getTaskVariants() != null ? config.getTaskVariants().size() : 0,
                                config.getTaskSetVariants() != null ? config.getTaskSetVariants().size() : 0);
                    } else {
                        failedCount++;
                        log.error("Failed to load fetch catalog config for {}: file {} returned null", name, filePath);
                    }
                } catch (Exception e) {
                    failedCount++;
                    log.error("Failed to load fetch catalog config for {} from {}", name, filePath, e);
                }
            }

            if (configMap.isEmpty()) {
                throw new IllegalStateException("No fetch catalog configurations loaded. Application cannot start.");
            }
            log.info("Fetch catalog loading completed. Loaded: {}, Failed: {}", configMap.size(), failedCount);
            log.info("Fetch catalog data types: {}", configMap.keySet());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load fetch catalog configurations from " + CATALOG_INDEX_PATH, e);
        }
    }

    /**
     * 对单个数据类型配置做基本校验：
     * <ul>
     *   <li>taskVariants 的 subType 不能重复</li>
     *   <li>taskSetVariants 的 subType 不能重复</li>
     *   <li>taskSetVariants 的 outputTaskVariantSubType 必须指向合法的 taskVariant</li>
     * </ul>
     */
    private void validateConfig(String dataType, FetchDataTypeConfig config) {
        // 校验 taskVariant subType 唯一性
        if (config.getTaskVariants() != null) {
            Set<Integer> seen = new HashSet<>();
            for (TaskVariantConfig tv : config.getTaskVariants()) {
                if (!seen.add(tv.getSubType())) {
                    log.warn("[{}] 存在重复的 taskVariant subType: {}", dataType, tv.getSubType());
                }
            }
        }
        // 校验 taskSetVariant subType 唯一性及 outputTaskVariantSubType 引用合法性
        if (config.getTaskSetVariants() != null) {
            Set<Integer> seen = new HashSet<>();
            for (TaskSetVariantConfig tsv : config.getTaskSetVariants()) {
                if (!seen.add(tsv.getSubType())) {
                    log.warn("[{}] 存在重复的 taskSetVariant subType: {}", dataType, tsv.getSubType());
                }
                if (config.findTaskVariant(tsv.getOutputTaskVariantSubType()) == null) {
                    log.warn("[{}] taskSetVariant subType={} 的 outputTaskVariantSubType={} 未指向合法的 taskVariant",
                            dataType, tsv.getSubType(), tsv.getOutputTaskVariantSubType());
                }
            }
        }
    }

    /**
     * 获取所有已加载的数据类型配置（只读视图）。
     *
     * @return 不可修改的 dataType -&gt; 配置 映射，迭代顺序与加载顺序一致
     */
    public Map<String, FetchDataTypeConfig> getAllConfigs() {
        return Collections.unmodifiableMap(configMap);
    }

    /**
     * 获取指定数据类型的配置。
     *
     * @param dataType 数据类型标识，如 {@code "index_quote"}
     * @return 对应配置，未找到时返回 {@code null}
     */
    public FetchDataTypeConfig findConfig(String dataType) {
        return configMap.get(dataType);
    }

    /**
     * 获取所有支持的数据类型名称列表。
     * 返回顺序与加载顺序一致（底层使用 {@link LinkedHashMap}）。
     *
     * @return 数据类型名称列表
     */
    public List<String> listAllDataTypes() {
        return List.copyOf(configMap.keySet());
    }

    /**
     * 查找指定数据类型和 subType 的 taskVariant 配置。
     *
     * @param dataType 数据类型标识
     * @param subType  task_sub_type 编号
     * @return 对应的 taskVariant 配置，未找到时返回 {@code null}
     */
    public TaskVariantConfig findTaskVariant(String dataType, int subType) {
        FetchDataTypeConfig config = configMap.get(dataType);
        if (config == null) return null;
        return config.findTaskVariant(subType);
    }

    /**
     * 查找指定数据类型和 subType 的 taskSetVariant 配置。
     *
     * @param dataType 数据类型标识
     * @param subType  task_set_sub_type 编号
     * @return 对应的 taskSetVariant 配置，未找到时返回 {@code null}
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

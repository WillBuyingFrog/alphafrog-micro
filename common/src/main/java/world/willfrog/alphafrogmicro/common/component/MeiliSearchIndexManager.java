package world.willfrog.alphafrogmicro.common.component;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.model.IndexesQuery;
import com.meilisearch.sdk.model.Results;
import com.meilisearch.sdk.model.Settings;
import com.meilisearch.sdk.model.TaskInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * MeiliSearch 索引管理器
 * 负责索引的创建、配置和检查
 */
@Slf4j
public class MeiliSearchIndexManager {

    private final Client meiliClient;
    private final String indexName;
    private final String[] searchableAttributes;
    private final String[] filterableAttributes;
    private final String[] sortableAttributes;

    /**
     * 创建索引管理器
     *
     * @param meiliClient          MeiliSearch 客户端
     * @param indexName            索引名称
     * @param searchableAttributes 可搜索字段（按优先级排序）
     * @param filterableAttributes 可过滤字段
     * @param sortableAttributes   可排序字段
     */
    public MeiliSearchIndexManager(Client meiliClient, String indexName,
                                   String[] searchableAttributes,
                                   String[] filterableAttributes,
                                   String[] sortableAttributes) {
        this.meiliClient = meiliClient;
        this.indexName = indexName;
        this.searchableAttributes = searchableAttributes != null ? searchableAttributes : new String[0];
        this.filterableAttributes = filterableAttributes != null ? filterableAttributes : new String[0];
        this.sortableAttributes = sortableAttributes != null ? sortableAttributes : new String[0];
    }

    /**
     * 检查索引是否存在
     */
    public boolean indexExists() {
        try {
            IndexesQuery query = new IndexesQuery();
            Results<Index> indexes = meiliClient.getIndexes(query);
            if (indexes != null && indexes.getResults() != null) {
                for (Index idx : indexes.getResults()) {
                    if (indexName.equals(idx.getUid())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("检查索引 {} 存在性时出错: {}", indexName, e.getMessage());
            return false;
        }
    }

    /**
     * 创建索引（如果不存在）
     *
     * @return true 表示创建成功或已存在
     */
    public boolean createIndexIfNotExists() {
        if (indexExists()) {
            log.info("MeiliSearch 索引 {} 已存在，跳过创建", indexName);
            return true;
        }

        try {
            log.info("正在创建 MeiliSearch 索引: {}", indexName);
            meiliClient.createIndex(indexName, "ts_code");
            
            // 等待索引创建完成
            int maxRetries = 10;
            int retry = 0;
            while (!indexExists() && retry < maxRetries) {
                Thread.sleep(100);
                retry++;
            }
            
            if (!indexExists()) {
                log.error("索引 {} 创建后未能确认存在", indexName);
                return false;
            }
            
            log.info("MeiliSearch 索引 {} 创建成功", indexName);
            return true;
        } catch (Exception e) {
            log.error("创建 MeiliSearch 索引 {} 失败: {}", indexName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 配置索引设置
     */
    public boolean configureIndex() {
        try {
            Index index = meiliClient.index(indexName);
            Settings settings = new Settings();
            
            // 设置可搜索字段
            if (searchableAttributes.length > 0) {
                settings.setSearchableAttributes(searchableAttributes);
            }
            
            // 设置可过滤字段
            if (filterableAttributes.length > 0) {
                settings.setFilterableAttributes(filterableAttributes);
            }
            
            // 设置可排序字段
            if (sortableAttributes.length > 0) {
                settings.setSortableAttributes(sortableAttributes);
            }
            
            // 设置排名规则（默认 + 相关性优化）
            settings.setRankingRules(new String[]{
                "words",
                "typo",
                "proximity",
                "attribute",
                "sort",
                "exactness"
            });
            
            index.updateSettings(settings);
            log.info("MeiliSearch 索引 {} 配置更新成功", indexName);
            return true;
        } catch (Exception e) {
            log.error("配置 MeiliSearch 索引 {} 失败: {}", indexName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 初始化索引（创建 + 配置）
     */
    public boolean initializeIndex() {
        if (!createIndexIfNotExists()) {
            return false;
        }
        return configureIndex();
    }

    /**
     * 获取索引统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        try {
            if (!indexExists()) {
                stats.put("exists", false);
                stats.put("documentCount", 0);
                return stats;
            }
            
            Index index = meiliClient.index(indexName);
            stats.put("exists", true);
            stats.put("documentCount", index.getStats().getNumberOfDocuments());
            return stats;
        } catch (Exception e) {
            log.warn("获取索引 {} 统计信息失败: {}", indexName, e.getMessage());
            stats.put("exists", false);
            stats.put("error", e.getMessage());
            return stats;
        }
    }

    public String getIndexName() {
        return indexName;
    }
}

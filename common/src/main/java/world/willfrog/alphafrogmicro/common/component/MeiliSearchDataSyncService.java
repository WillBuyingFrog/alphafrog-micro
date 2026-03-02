package world.willfrog.alphafrogmicro.common.component;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.model.TaskInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * MeiliSearch 数据同步服务
 * 负责将数据库数据批量导入到 MeiliSearch
 * 
 * 支持：
 * 1. 异步全量同步（服务启动时触发）
 * 2. 定时增量/全量同步
 * 3. 同步状态监控
 * 
 * 注意：MeiliSearch 的文档 ID 不能包含 '.'，需要将 ts_code 中的 '.' 替换为 '_'
 */
@Slf4j
public class MeiliSearchDataSyncService {

    /**
     * 转换 ts_code 为 MeiliSearch 合法的文档 ID
     * MeiliSearch 文档 ID 只能包含：字母、数字、连字符(-)、下划线(_)
     * 例如：000001.SZ -> 000001_SZ
     */
    public static String toMeiliId(String tsCode) {
        if (tsCode == null) return "";
        return tsCode.replace('.', '_');
    }

    /**
     * 将 MeiliSearch 文档 ID 转换回原始 ts_code
     * 例如：000001_SZ -> 000001.SZ
     */
    public static String fromMeiliId(String meiliId) {
        if (meiliId == null) return "";
        return meiliId.replace('_', '.');
    }

    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final int DEFAULT_THREAD_POOL_SIZE = 2;
    private static final long BATCH_SUBMIT_INTERVAL_MS = 100; // 每批提交间隔，避免打爆 MeiliSearch

    private final Client meiliClient;
    private final String indexName;
    private final int batchSize;
    private final ExecutorService executorService;
    
    // 同步状态
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final AtomicLong lastSyncTime = new AtomicLong(0);
    private final AtomicInteger lastSyncedCount = new AtomicInteger(0);
    private volatile String lastSyncStatus = "NEVER"; // NEVER / SYNCING / SUCCESS / FAILED
    private volatile String lastErrorMessage = null;

    public MeiliSearchDataSyncService(Client meiliClient, String indexName) {
        this(meiliClient, indexName, DEFAULT_BATCH_SIZE);
    }

    public MeiliSearchDataSyncService(Client meiliClient, String indexName, int batchSize) {
        this.meiliClient = meiliClient;
        this.indexName = indexName;
        this.batchSize = batchSize;
        this.executorService = Executors.newFixedThreadPool(DEFAULT_THREAD_POOL_SIZE, r -> {
            Thread t = new Thread(r, "meili-sync-" + indexName);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 异步全量同步
     * 
     * @param dataFetcher 数据获取函数 (offset, limit) -> 数据列表
     * @param docConverter 文档转换函数 数据 -> MeiliSearch 文档 Map
     * @param totalCount 数据总数预估（用于计算批次）
     * @return CompletableFuture 可以链式处理结果
     */
    public <T> CompletableFuture<SyncResult> asyncFullSync(
            FetchFunction<T> dataFetcher,
            Function<T, Map<String, Object>> docConverter,
            int totalCount) {
        
        if (isSyncing.compareAndSet(false, true)) {
            lastSyncStatus = "SYNCING";
            lastErrorMessage = null;
            
            return CompletableFuture.supplyAsync(() -> {
                try {
                    log.info("[{}] 开始全量同步到 MeiliSearch，预估数据量: {}", indexName, totalCount);
                    SyncResult result = doFullSync(dataFetcher, docConverter, totalCount);
                    
                    lastSyncTime.set(System.currentTimeMillis());
                    lastSyncedCount.set(result.getSyncedCount());
                    lastSyncStatus = result.isSuccess() ? "SUCCESS" : "FAILED";
                    if (!result.isSuccess()) {
                        lastErrorMessage = result.getErrorMessage();
                    }
                    
                    log.info("[{}] 全量同步完成: 成功导入 {} 条文档", indexName, result.getSyncedCount());
                    return result;
                    
                } catch (Exception e) {
                    log.error("[{}] 全量同步失败: {}", indexName, e.getMessage(), e);
                    lastSyncStatus = "FAILED";
                    lastErrorMessage = e.getMessage();
                    return SyncResult.failed(e.getMessage());
                } finally {
                    isSyncing.set(false);
                }
            }, executorService);
        } else {
            log.warn("[{}] 同步任务已在进行中，跳过本次请求", indexName);
            return CompletableFuture.completedFuture(SyncResult.skipped("同步任务已在进行中"));
        }
    }

    /**
     * 执行全量同步（内部方法）
     */
    private <T> SyncResult doFullSync(
            FetchFunction<T> dataFetcher,
            Function<T, Map<String, Object>> docConverter,
            int totalCount) {
        
        try {
            Index index = meiliClient.index(indexName);
            int syncedCount = 0;
            int batchNum = 0;
            int offset = 0;
            
            // 如果数据量大，先清空索引避免重复
            if (totalCount > 0) {
                try {
                    log.info("[{}] 清空旧数据...", indexName);
                    TaskInfo deleteTask = index.deleteAllDocuments();
                    log.debug("[{}] 删除任务已提交，taskUid: {}", indexName, deleteTask.getTaskUid());
                    Thread.sleep(500); // 等待删除完成
                } catch (Exception e) {
                    log.warn("[{}] 清空旧数据失败（可能索引为空）: {}", indexName, e.getMessage());
                }
            }
            
            while (true) {
                // 获取一批数据
                List<T> batch = dataFetcher.fetch(offset, batchSize);
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                
                // 转换为文档
                List<Map<String, Object>> documents = new ArrayList<>(batch.size());
                for (T item : batch) {
                    try {
                        Map<String, Object> doc = docConverter.apply(item);
                        if (doc != null && !doc.isEmpty()) {
                            documents.add(doc);
                        }
                    } catch (Exception e) {
                        log.warn("[{}] 文档转换失败: {}", indexName, e.getMessage());
                    }
                }
                
                if (!documents.isEmpty()) {
                    // 批量导入 - 使用 JSON 字符串方式
                    try {
                        String documentsJson = convertToJson(documents);
                        TaskInfo task = index.addDocuments(documentsJson);
                        syncedCount += documents.size();
                        batchNum++;
                        
                        if (batchNum % 5 == 0) {
                            log.info("[{}] 已同步 {} 批，共 {} 条文档", indexName, batchNum, syncedCount);
                        }
                        
                        // 控制提交速度
                        Thread.sleep(BATCH_SUBMIT_INTERVAL_MS);
                    } catch (Exception e) {
                        log.error("[{}] 批量导入失败: {}", indexName, e.getMessage(), e);
                    }
                }
                
                if (batch.size() < batchSize) {
                    break; // 最后一批
                }
                offset += batchSize;
            }
            
            return SyncResult.success(syncedCount, batchNum);
            
        } catch (Exception e) {
            log.error("[{}] 同步过程异常: {}", indexName, e.getMessage(), e);
            return SyncResult.failed(e.getMessage());
        }
    }
    
    /**
     * 将文档列表转换为 JSON 字符串
     */
    private String convertToJson(List<Map<String, Object>> documents) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < documents.size(); i++) {
            Map<String, Object> doc = documents.get(i);
            if (i > 0) sb.append(",");
            sb.append(convertMapToJson(doc));
        }
        sb.append("]");
        return sb.toString();
    }
    
    /**
     * 将 Map 转换为 JSON 字符串（简化版）
     */
    private String convertMapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Number) {
                sb.append(value);
            } else if (value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append("\"").append(escapeJson(value.toString())).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }
    
    /**
     * 转义 JSON 字符串中的特殊字符
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\b", "\\b")
                  .replace("\f", "\\f")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    /**
     * 获取同步状态
     */
    public Map<String, Object> getSyncStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("indexName", indexName);
        status.put("isSyncing", isSyncing.get());
        status.put("lastSyncStatus", lastSyncStatus);
        status.put("lastSyncTime", lastSyncTime.get() > 0 ? 
            new java.util.Date(lastSyncTime.get()).toString() : "从未");
        status.put("lastSyncedCount", lastSyncedCount.get());
        status.put("lastErrorMessage", lastErrorMessage);
        return status;
    }

    /**
     * 是否正在同步中
     */
    public boolean isSyncing() {
        return isSyncing.get();
    }

    /**
     * 关闭服务（释放线程池）
     */
    public void shutdown() {
        executorService.shutdown();
    }

    // ========== 内部类 ==========

    /**
     * 数据获取函数式接口
     */
    @FunctionalInterface
    public interface FetchFunction<T> {
        List<T> fetch(int offset, int limit);
    }

    /**
     * 同步结果
     */
    public static class SyncResult {
        private final boolean success;
        private final int syncedCount;
        private final int batchCount;
        private final String message;
        private final String errorMessage;

        private SyncResult(boolean success, int syncedCount, int batchCount, 
                          String message, String errorMessage) {
            this.success = success;
            this.syncedCount = syncedCount;
            this.batchCount = batchCount;
            this.message = message;
            this.errorMessage = errorMessage;
        }

        public static SyncResult success(int syncedCount, int batchCount) {
            return new SyncResult(true, syncedCount, batchCount, 
                String.format("成功同步 %d 条文档（%d 批）", syncedCount, batchCount), null);
        }

        public static SyncResult failed(String errorMessage) {
            return new SyncResult(false, 0, 0, "同步失败", errorMessage);
        }

        public static SyncResult skipped(String reason) {
            return new SyncResult(true, 0, 0, "跳过: " + reason, null);
        }

        public boolean isSuccess() { return success; }
        public int getSyncedCount() { return syncedCount; }
        public int getBatchCount() { return batchCount; }
        public String getMessage() { return message; }
        public String getErrorMessage() { return errorMessage; }
    }
}

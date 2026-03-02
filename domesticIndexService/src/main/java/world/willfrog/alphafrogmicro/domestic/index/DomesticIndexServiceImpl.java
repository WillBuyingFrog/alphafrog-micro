package world.willfrog.alphafrogmicro.domestic.index;

import lombok.extern.slf4j.Slf4j;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.model.SearchResult;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.component.MeiliSearchIndexManager;
import world.willfrog.alphafrogmicro.common.component.MeiliSearchDataSyncService;
import world.willfrog.alphafrogmicro.common.dao.domestic.calendar.TradeCalendarDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexQuoteDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexWeightDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexDaily;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexInfo;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexWeight;
import world.willfrog.alphafrogmicro.domestic.idl.*;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticIndexServiceTriple.DomesticIndexServiceImplBase;
import world.willfrog.alphafrogmicro.domestic.index.service.IndexDataCompletenessService;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@DubboService
@Service
@Slf4j
public class DomesticIndexServiceImpl extends DomesticIndexServiceImplBase {

    private static final String MEILI_HOST_PROP = "meilisearch.host";
    private static final String MEILI_API_KEY_PROP = "meilisearch.api-key";
    private static final String MEILI_ENABLED_PROP = "advanced.meili-enabled";
    private static final String MEILI_AUTO_SYNC_PROP = "advanced.meili-auto-sync";
    private static final String DEFAULT_MEILI_HOST = "http://localhost:7700";
    private static final String DEFAULT_MEILI_API_KEY = "alphafrog_search_key";

    private final IndexInfoDao indexInfoDao;
    private final IndexQuoteDao indexQuoteDao;
    private final IndexWeightDao indexWeightDao;
    private final IndexDataCompletenessService indexDataCompletenessService;
    private final TradeCalendarDao tradeCalendarDao;
    private final Environment environment;
    private volatile Client meiliClient;
    private volatile String meiliClientHost;
    private volatile String meiliClientApiKey;
    
    // MeiliSearch 索引管理
    private volatile MeiliSearchIndexManager indexManager;
    private volatile MeiliSearchDataSyncService syncService;


    public DomesticIndexServiceImpl(IndexInfoDao indexInfoDao,
                                    IndexQuoteDao indexQuoteDao,
                                    IndexWeightDao indexWeightDao,
                                    IndexDataCompletenessService indexDataCompletenessService,
                                    TradeCalendarDao tradeCalendarDao,
                                    Environment environment) {
        this.indexInfoDao = indexInfoDao;
        this.indexQuoteDao = indexQuoteDao;
        this.indexWeightDao = indexWeightDao;
        this.indexDataCompletenessService = indexDataCompletenessService;
        this.tradeCalendarDao = tradeCalendarDao;
        this.environment = environment;
    }

    /**
     * 服务启动时初始化 MeiliSearch 索引
     */
    @PostConstruct
    public void init() {
        if (!isMeiliEnabled()) {
            log.info("MeiliSearch 已禁用，跳过索引初始化");
            return;
        }

        try {
            Client client = getMeiliClient();
            
            // 创建索引管理器
            indexManager = new MeiliSearchIndexManager(
                client,
                "indices",
                new String[]{"name", "ts_code", "full_name", "market", "publisher"}, // 可搜索字段
                new String[]{"market", "publisher"}, // 可过滤字段
                new String[]{"name", "ts_code"} // 可排序字段
            );
            
            // 初始化索引（创建 + 配置）
            boolean initialized = indexManager.initializeIndex();
            if (initialized) {
                log.info("MeiliSearch indices 索引初始化成功");
                
                // 创建同步服务
                syncService = new MeiliSearchDataSyncService(client, "indices", 500);
                
                // 检查是否需要自动同步
                if (isAutoSyncEnabled()) {
                    // 异步触发全量同步
                    triggerFullSync();
                } else {
                    log.info("MeiliSearch 自动同步已禁用，跳过数据导入");
                }
            } else {
                log.error("MeiliSearch indices 索引初始化失败");
            }
            
        } catch (Exception e) {
            log.error("初始化 MeiliSearch 索引失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 触发全量同步
     */
    private void triggerFullSync() {
        if (syncService == null || syncService.isSyncing()) {
            return;
        }

        // 获取指数总数
        int totalCount = indexInfoDao.getIndexInfoCount();
        
        // 定义数据获取函数
        MeiliSearchDataSyncService.FetchFunction<IndexInfo> fetchFunction = 
            (offset, limit) -> indexInfoDao.getAllIndexInfo(offset, limit);
        
        // 定义文档转换函数
        Function<IndexInfo, Map<String, Object>> docConverter = this::convertToMeiliDocument;
        
        // 异步执行同步
        syncService.asyncFullSync(fetchFunction, docConverter, totalCount)
            .thenAccept(result -> {
                if (result.isSuccess()) {
                    log.info("[indices] MeiliSearch 同步完成: {}", result.getMessage());
                } else {
                    log.error("[indices] MeiliSearch 同步失败: {}", result.getErrorMessage());
                }
            });
    }

    /**
     * 定时全量同步（每天凌晨 3:30）
     */
    @Scheduled(cron = "${advanced.meili-sync-cron-indices:0 30 3 * * ?}")
    public void scheduledFullSync() {
        if (!isMeiliEnabled() || !isAutoSyncEnabled()) {
            return;
        }
        
        log.info("[indices] 定时同步任务触发");
        triggerFullSync();
    }

    /**
     * 将 IndexInfo 转换为 MeiliSearch 文档
     * 注意：ts_code 需要转换为 MeiliSearch 合法的文档 ID（将 . 替换为 _）
     */
    private Map<String, Object> convertToMeiliDocument(IndexInfo index) {
        Map<String, Object> doc = new HashMap<>();
        // MeiliSearch 文档 ID 不能包含 '.'，需要转换
        doc.put("ts_code", MeiliSearchDataSyncService.toMeiliId(index.getTsCode()));
        doc.put("name", index.getName());
        doc.put("full_name", index.getFullName());
        doc.put("market", index.getMarket());
        doc.put("publisher", index.getPublisher());
        doc.put("index_type", index.getIndexType());
        doc.put("category", index.getCategory());
        return doc;
    }


    @Override
    public DomesticIndexInfoByTsCodeResponse getDomesticIndexInfoByTsCode(DomesticIndexInfoByTsCodeRequest request) {

        List<IndexInfo> indexInfoList;
        try {
            // 使用合理的分页参数，避免返回过多数据
            indexInfoList = indexInfoDao.getIndexInfoByTsCode(request.getTsCode(), 10, 0);
        } catch (Exception e) {
            log.error("Error occurred while getting index info for tsCode: {}", request.getTsCode(), e);
            // 数据库异常时返回空响应
            return DomesticIndexInfoByTsCodeResponse.newBuilder().build();
        }

        if (indexInfoList.isEmpty()){
            log.warn("Index info not found for tsCode: {}", request.getTsCode());
            // 数据未找到时返回空响应而不是null
            return DomesticIndexInfoByTsCodeResponse.newBuilder().build();
        }

        DomesticIndexInfoByTsCodeResponse.Builder responseBuilder = DomesticIndexInfoByTsCodeResponse.newBuilder();

        IndexInfo indexInfo = indexInfoList.get(0);
        DomesticIndexInfoFullItem.Builder builder = DomesticIndexInfoFullItem.newBuilder();
        builder.setTsCode(indexInfo.getTsCode()).setName(indexInfo.getName())
                .setFullname(indexInfo.getFullName()).setMarket(indexInfo.getMarket());
        if (indexInfo.getPublisher() != null) {
            builder.setPublisher(indexInfo.getPublisher());
        }
        if (indexInfo.getIndexType() != null) {
            builder.setIndexType(indexInfo.getIndexType());
        }
        if (indexInfo.getCategory() != null) {
            builder.setCategory(indexInfo.getCategory());
        }
        if (indexInfo.getBaseDate() != null) {
            builder.setBaseDate(indexInfo.getBaseDate());
        }
        if (indexInfo.getBasePoint() != null) {
            builder.setBasePoint(indexInfo.getBasePoint());
        }
        if (indexInfo.getListDate() != null) {
            builder.setListDate(indexInfo.getListDate());
        }
        if (indexInfo.getWeightRule() != null) {
            builder.setWeightRule(indexInfo.getWeightRule());
        }
        if (indexInfo.getDesc() != null) {
            builder.setDesc(indexInfo.getDesc());
        }
        if (indexInfo.getExpDate() != null) {
            builder.setExpDate(indexInfo.getExpDate());
        }
        responseBuilder.setItem(builder.build());

        return responseBuilder.build();
    }

    @Override
    public DomesticIndexSearchResponse searchDomesticIndex(DomesticIndexSearchRequest request) {

        String query = request.getQuery();
        if (query == null || query.trim().isEmpty()) {
            return DomesticIndexSearchResponse.newBuilder().build();
        }
        String normalizedQuery = query.trim();
        DomesticIndexSearchResponse.Builder responseBuilder = DomesticIndexSearchResponse.newBuilder();
        if (isMeiliEnabled()) {
            try {
                Index index = getMeiliClient().index("indices");
                SearchResult searchResult = (SearchResult) index.search(
                        SearchRequest.builder().q(normalizedQuery).limit(200).build());
                for (Object hitObj : searchResult.getHits()) {
                    if (!(hitObj instanceof Map<?, ?> hit)) {
                        continue;
                    }
                    // 将 MeiliSearch 的 ts_code 转换回原始格式（将 _ 替换为 .）
                    String meiliTsCode = stringValue(hit.get("ts_code"));
                    String originalTsCode = MeiliSearchDataSyncService.fromMeiliId(meiliTsCode);
                    DomesticIndexInfoSimpleItem.Builder itemBuilder = DomesticIndexInfoSimpleItem.newBuilder()
                            .setTsCode(originalTsCode)
                            .setName(stringValue(hit.get("name")))
                            .setFullname(stringValue(hit.get("full_name")))
                            .setMarket(stringValue(hit.get("market")));
                    responseBuilder.addItems(itemBuilder.build());
                }
                if (responseBuilder.getItemsCount() > 0) {
                    return responseBuilder.build();
                }
            } catch (Exception e) {
                log.warn("MeiliSearch query failed for index search query={}", normalizedQuery, e);
            }
        }
        List<IndexInfo> indexInfoList;
        try {
            // 单条 SQL + 相关性排序，避免高热度关键词下"随机截断"导致基础指数缺失
            indexInfoList = indexInfoDao.searchIndexInfo(normalizedQuery, 200, 0);
        } catch (Exception e) {
            log.error("Error occurred while searching index info with query: {}", normalizedQuery, e);
            // 搜索异常时返回空响应
            return DomesticIndexSearchResponse.newBuilder().build();
        }
        for (IndexInfo indexInfo : indexInfoList) {
            DomesticIndexInfoSimpleItem.Builder itemBuilder = DomesticIndexInfoSimpleItem.newBuilder()
                    .setTsCode(indexInfo.getTsCode()).setName(indexInfo.getName())
                    .setFullname(indexInfo.getFullName()).setMarket(indexInfo.getMarket());
            responseBuilder.addItems(itemBuilder.build());
        }

        return responseBuilder.build();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean isMeiliEnabled() {
        return Boolean.parseBoolean(environment.getProperty(MEILI_ENABLED_PROP, "true"));
    }

    private boolean isAutoSyncEnabled() {
        return Boolean.parseBoolean(environment.getProperty(MEILI_AUTO_SYNC_PROP, "true"));
    }

    private Client getMeiliClient() {
        String host = environment.getProperty(MEILI_HOST_PROP, DEFAULT_MEILI_HOST);
        String apiKey = environment.getProperty(MEILI_API_KEY_PROP, DEFAULT_MEILI_API_KEY);

        Client localClient = meiliClient;
        if (localClient == null
                || !Objects.equals(meiliClientHost, host)
                || !Objects.equals(meiliClientApiKey, apiKey)) {
            synchronized (this) {
                localClient = meiliClient;
                if (localClient == null
                        || !Objects.equals(meiliClientHost, host)
                        || !Objects.equals(meiliClientApiKey, apiKey)) {
                    meiliClient = new Client(new Config(host, apiKey));
                    meiliClientHost = host;
                    meiliClientApiKey = apiKey;
                    localClient = meiliClient;
                    log.info("MeiliSearch client refreshed for index service, host={}", host);
                }
            }
        }
        return localClient;
    }

    @Override
    public DomesticIndexDailyByTsCodeAndDateRangeResponse getDomesticIndexDailyByTsCodeAndDateRange(
            DomesticIndexDailyByTsCodeAndDateRangeRequest request) {

        List<IndexDaily> indexDailyList;
        try {
            indexDailyList = indexQuoteDao.getIndexDailiesByTsCodeAndDateRange(
                    request.getTsCode(), request.getStartDate(), request.getEndDate()
            );
        } catch (Exception e) {
            log.error("Error occurred while getting index daily data for tsCode: {}, dateRange: {}-{}", 
                     request.getTsCode(), request.getStartDate(), request.getEndDate(), e);
            // 数据库异常时返回空响应
            return DomesticIndexDailyByTsCodeAndDateRangeResponse.newBuilder().build();
        }

        if(indexDailyList.isEmpty()) {
            log.warn("Index daily data not found for tsCode: {}, dateRange: {}-{}", 
                    request.getTsCode(), request.getStartDate(), request.getEndDate());
            return DomesticIndexDailyByTsCodeAndDateRangeResponse.newBuilder().build();
        }

        DomesticIndexDailyByTsCodeAndDateRangeResponse.Builder responseBuilder =
                DomesticIndexDailyByTsCodeAndDateRangeResponse.newBuilder();

        for (IndexDaily indexDaily : indexDailyList) {
            DomesticIndexDailyItem.Builder itemBuilder = DomesticIndexDailyItem.newBuilder()
                    .setTsCode(indexDaily.getTsCode()).setTradeDate(indexDaily.getTradeDate())
                    .setClose(indexDaily.getClose()).setOpen(indexDaily.getOpen())
                    .setHigh(indexDaily.getHigh()).setLow(indexDaily.getLow())
                    .setPreClose(indexDaily.getPreClose()).setChange(indexDaily.getChange())
                    .setPctChg(indexDaily.getPctChg()).setVol(indexDaily.getVol())
                    .setAmount(indexDaily.getAmount());

            responseBuilder.addItems(itemBuilder.build());
        }

        IndexDataCompletenessService.IndexCompletenessResult completeness =
                indexDataCompletenessService.evaluate(
                        request.getTsCode(),
                        request.getStartDate(),
                        request.getEndDate()
                );

        responseBuilder.setComplete(completeness.isComplete())
                .setExpectedTradingDays(completeness.getExpectedTradingDays())
                .setActualTradingDays(completeness.getActualTradingDays())
                .setMissingCount(completeness.getMissingCount())
                .setUpstreamGap(completeness.isUpstreamGap());

        if (completeness.getMissingDates() != null && !completeness.getMissingDates().isEmpty()) {
            responseBuilder.addAllMissingDates(completeness.getMissingDates());
        }

        return responseBuilder.build();
    }

    @Override
    public DomesticTradingDaysCountResponse getTradingDaysCountByDateRange(
            DomesticTradingDaysCountRequest request) {
        String exchange = request.getExchange();
        if (exchange == null || exchange.trim().isEmpty()) {
            exchange = "SSE";
        }

        long startDate = request.getStartDate();
        long endDate = request.getEndDate();
        if (startDate > endDate) {
            long tmp = startDate;
            startDate = endDate;
            endDate = tmp;
        }

        int count;
        try {
            count = tradeCalendarDao.countTradingDaysByRange(exchange, startDate, endDate);
        } catch (Exception e) {
            log.error("Error occurred while counting trading days: exchange={}, dateRange={}-{}",
                    exchange, startDate, endDate, e);
            return DomesticTradingDaysCountResponse.newBuilder()
                    .setExchange(exchange)
                    .setStartDate(startDate)
                    .setEndDate(endDate)
                    .setTradingDaysCount(0)
                    .build();
        }

        return DomesticTradingDaysCountResponse.newBuilder()
                .setExchange(exchange)
                .setStartDate(startDate)
                .setEndDate(endDate)
                .setTradingDaysCount(count)
                .build();
    }


    @Override
    public DomesticIndexWeightByTsCodeAndDateRangeResponse getDomesticIndexWeightByTsCodeAndDateRange(
            DomesticIndexWeightByTsCodeAndDateRangeRequest request) {

        List<IndexWeight> indexWeightList;
        try {
            indexWeightList = indexWeightDao.getIndexWeightsByTsCodeAndDateRange(
                    request.getTsCode(), request.getStartDate(), request.getEndDate()
            );
        } catch (Exception e) {
            log.error("Error occurred while getting index weight data for tsCode: {}, dateRange: {}-{}", 
                     request.getTsCode(), request.getStartDate(), request.getEndDate(), e);
            // 数据库异常时返回空响应
            return DomesticIndexWeightByTsCodeAndDateRangeResponse.newBuilder().build();
        }

        if (indexWeightList.isEmpty()) {
            log.warn("Index weight data not found for tsCode: {}, dateRange: {}-{}", 
                    request.getTsCode(), request.getStartDate(), request.getEndDate());
            return DomesticIndexWeightByTsCodeAndDateRangeResponse.newBuilder().build();
        }

        DomesticIndexWeightByTsCodeAndDateRangeResponse.Builder builder =
                DomesticIndexWeightByTsCodeAndDateRangeResponse.newBuilder();

        for (IndexWeight indexWeight : indexWeightList) {
            DomesticIndexWeightItem.Builder itemBuilder = DomesticIndexWeightItem.newBuilder()
                    .setIndexCode(indexWeight.getIndexCode()).setConCode(indexWeight.getConCode())
                    .setTradeDate(indexWeight.getTradeDate())
                    .setWeight(indexWeight.getWeight());

            builder.addItems(itemBuilder.build());
        }

        return builder.build();
    }


    @Override
    public DomesticIndexWeightByConCodeAndDateRangeResponse getDomesticIndexWeightByConCodeAndDateRange(
            DomesticIndexWeightByConCodeAndDateRangeRequest request) {
        
        List<IndexWeight> indexWeightList;
        try {
            indexWeightList = indexWeightDao.getIndexWeightsByConCodeAndDateRange(
                    request.getConCode(), request.getStartDate(), request.getEndDate()
            );
        } catch (Exception e) {
            log.error("Error occurred while getting index weight data for conCode: {}, dateRange: {}-{}", 
                     request.getConCode(), request.getStartDate(), request.getEndDate(), e);
            // 数据库异常时返回空响应
            return DomesticIndexWeightByConCodeAndDateRangeResponse.newBuilder().build();
        }

        if (indexWeightList.isEmpty()) {
            log.warn("Index weight data not found for conCode: {}, dateRange: {}-{}", 
                    request.getConCode(), request.getStartDate(), request.getEndDate());
            return DomesticIndexWeightByConCodeAndDateRangeResponse.newBuilder().build();
        }

        DomesticIndexWeightByConCodeAndDateRangeResponse.Builder builder =
                DomesticIndexWeightByConCodeAndDateRangeResponse.newBuilder();

        for (IndexWeight indexWeight : indexWeightList) {
            DomesticIndexWeightItem.Builder itemBuilder = DomesticIndexWeightItem.newBuilder()
                    .setIndexCode(indexWeight.getIndexCode()).setConCode(indexWeight.getConCode())
                    .setTradeDate(indexWeight.getTradeDate())
                    .setWeight(indexWeight.getWeight());

            builder.addItems(itemBuilder.build());
        }
        
        return builder.build();

    }


}

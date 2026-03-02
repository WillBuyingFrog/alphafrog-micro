package world.willfrog.alphafrogmicro.domestic.stock;

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
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockQuoteDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockDaily;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockInfo;
import world.willfrog.alphafrogmicro.domestic.idl.*;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticStockServiceTriple.*;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@Service
@DubboService
@Slf4j
public class DomesticStockServiceImpl extends DomesticStockServiceImplBase {

    private static final String MEILI_HOST_PROP = "meilisearch.host";
    private static final String MEILI_API_KEY_PROP = "meilisearch.api-key";
    private static final String MEILI_ENABLED_PROP = "advanced.meili-enabled";
    private static final String MEILI_AUTO_SYNC_PROP = "advanced.meili-auto-sync";
    private static final String DEFAULT_MEILI_HOST = "http://localhost:7700";
    private static final String DEFAULT_MEILI_API_KEY = "alphafrog_search_key";

    private final StockInfoDao stockInfoDao;
    private final StockQuoteDao stockQuoteDao;
    private final Environment environment;
    private volatile Client meiliClient;
    private volatile String meiliClientHost;
    private volatile String meiliClientApiKey;
    
    // MeiliSearch 索引管理
    private volatile MeiliSearchIndexManager indexManager;
    private volatile MeiliSearchDataSyncService syncService;

    public DomesticStockServiceImpl(StockInfoDao stockInfoDao,
                                    StockQuoteDao stockQuoteDao,
                                    Environment environment) {
        this.stockInfoDao = stockInfoDao;
        this.stockQuoteDao = stockQuoteDao;
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
                "stocks",
                new String[]{"name", "symbol", "ts_code", "full_name", "en_name", "area", "industry"}, // 可搜索字段
                new String[]{"market", "exchange", "area", "industry"}, // 可过滤字段
                new String[]{"name", "ts_code"} // 可排序字段
            );
            
            // 初始化索引（创建 + 配置）
            boolean initialized = indexManager.initializeIndex();
            if (initialized) {
                log.info("MeiliSearch stocks 索引初始化成功");
                
                // 创建同步服务
                syncService = new MeiliSearchDataSyncService(client, "stocks", 500);
                
                // 检查是否需要自动同步
                if (isAutoSyncEnabled()) {
                    // 异步触发全量同步
                    triggerFullSync();
                } else {
                    log.info("MeiliSearch 自动同步已禁用，跳过数据导入");
                }
            } else {
                log.error("MeiliSearch stocks 索引初始化失败");
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

        // 获取股票总数
        int totalCount = stockInfoDao.getStockInfoCount();
        
        // 定义数据获取函数
        MeiliSearchDataSyncService.FetchFunction<StockInfo> fetchFunction = 
            (offset, limit) -> stockInfoDao.getAllStockInfo(offset, limit);
        
        // 定义文档转换函数
        Function<StockInfo, Map<String, Object>> docConverter = this::convertToMeiliDocument;
        
        // 异步执行同步
        syncService.asyncFullSync(fetchFunction, docConverter, totalCount)
            .thenAccept(result -> {
                if (result.isSuccess()) {
                    log.info("[stocks] MeiliSearch 同步完成: {}", result.getMessage());
                } else {
                    log.error("[stocks] MeiliSearch 同步失败: {}", result.getErrorMessage());
                }
            });
    }

    /**
     * 定时全量同步（每天凌晨 3:00）
     */
    @Scheduled(cron = "${advanced.meili-sync-cron:0 0 3 * * ?}")
    public void scheduledFullSync() {
        if (!isMeiliEnabled() || !isAutoSyncEnabled()) {
            return;
        }
        
        log.info("[stocks] 定时同步任务触发");
        triggerFullSync();
    }

    /**
     * 将 StockInfo 转换为 MeiliSearch 文档
     * 注意：ts_code 需要转换为 MeiliSearch 合法的文档 ID（将 . 替换为 _）
     */
    private Map<String, Object> convertToMeiliDocument(StockInfo stock) {
        Map<String, Object> doc = new HashMap<>();
        // MeiliSearch 文档 ID 不能包含 '.'，需要转换
        doc.put("ts_code", MeiliSearchDataSyncService.toMeiliId(stock.getTsCode()));
        doc.put("symbol", stock.getSymbol());
        doc.put("name", stock.getName());
        doc.put("full_name", stock.getFullName());
        doc.put("en_name", stock.getEnName());
        doc.put("area", stock.getArea());
        doc.put("industry", stock.getIndustry());
        doc.put("market", stock.getMarket());
        doc.put("exchange", stock.getExchange());
        doc.put("cnspell", stock.getCnspell());
        return doc;
    }

    @Override
    public DomesticStockInfoByTsCodeResponse getStockInfoByTsCode(DomesticStockInfoByTsCodeRequest request) {
        String tsCode = request.getTsCode();

        List<StockInfo> stockInfoList = stockInfoDao.getStockInfoByTsCode(tsCode, 10, 0);

        if (stockInfoList == null || stockInfoList.isEmpty()) {
            log.warn("StockInfo not found for tsCode: {}", tsCode);
            return DomesticStockInfoByTsCodeResponse.newBuilder().build();
        }

        StockInfo stockInfo = stockInfoList.get(0);
        DomesticStockInfoFullItem.Builder builder = DomesticStockInfoFullItem.newBuilder();

        builder.setTsCode(stockInfo.getTsCode()).setSymbol(stockInfo.getSymbol())
                .setStockInfoId(-1).setName(stockInfo.getName());

        if (stockInfo.getArea() != null) {
            builder.setMarket(stockInfo.getArea());
        }

        if (stockInfo.getIndustry() != null) {
            builder.setExchange(stockInfo.getIndustry());
        }

        if (stockInfo.getFullName() != null) {
            builder.setFullName(stockInfo.getFullName());
        }

        if (stockInfo.getEnName() != null) {
            builder.setEnName(stockInfo.getEnName());
        }

        if (stockInfo.getCnspell() != null) {
            builder.setCnspell(stockInfo.getCnspell());
        }

        if (stockInfo.getMarket() != null) {
            builder.setMarket(stockInfo.getMarket());
        }

        if (stockInfo.getExchange() != null) {
            builder.setExchange(stockInfo.getExchange());
        }

        if (stockInfo.getCurrType() != null) {
            builder.setCurrType(stockInfo.getCurrType());
        }

        if (stockInfo.getListStatus() != null) {
            builder.setListStatus(stockInfo.getListStatus());
        }

        if (stockInfo.getListDate() != null) {
            builder.setListDate(stockInfo.getListDate());
        }

        if (stockInfo.getDelistDate() != null) {
            builder.setDelistDate(stockInfo.getDelistDate());
        }

        if (stockInfo.getIsHs() != null) {
            builder.setIsHs(stockInfo.getIsHs());
        }

        if (stockInfo.getActName() != null) {
            builder.setActName(stockInfo.getActName());
        }

        if (stockInfo.getActEntType() != null) {
            builder.setActEntType(stockInfo.getActEntType());
        }

        DomesticStockInfoFullItem item = builder.build();

        return DomesticStockInfoByTsCodeResponse.newBuilder().setItem(item).build();
    }

    @Override
    public DomesticStockSearchResponse searchStock(DomesticStockSearchRequest request) {
        String query = request.getQuery();
        if (query == null || query.trim().isEmpty()) {
            return DomesticStockSearchResponse.newBuilder().build();
        }
        String normalizedQuery = query.trim();
        DomesticStockSearchResponse.Builder responseBuilder = DomesticStockSearchResponse.newBuilder();
        if (isMeiliEnabled()) {
            try {
                Index index = getMeiliClient().index("stocks");
                SearchResult searchResult = (SearchResult) index.search(
                        SearchRequest.builder().q(normalizedQuery).limit(20).build());
                for (Object hitObj : searchResult.getHits()) {
                    if (!(hitObj instanceof Map<?, ?> hit)) {
                        continue;
                    }
                    // 将 MeiliSearch 的 ts_code 转换回原始格式（将 _ 替换为 .）
                    String meiliTsCode = stringValue(hit.get("ts_code"));
                    String originalTsCode = MeiliSearchDataSyncService.fromMeiliId(meiliTsCode);
                    DomesticStockInfoSimpleItem.Builder itemBuilder = DomesticStockInfoSimpleItem.newBuilder()
                            .setTsCode(originalTsCode)
                            .setSymbol(stringValue(hit.get("symbol")))
                            .setName(stringValue(hit.get("name")))
                            .setArea(stringValue(hit.get("area")))
                            .setIndustry(stringValue(hit.get("industry")));
                    responseBuilder.addItems(itemBuilder.build());
                }
                if (responseBuilder.getItemsCount() > 0) {
                    return responseBuilder.build();
                }
            } catch (Exception e) {
                log.warn("MeiliSearch query failed for stock search query={}", normalizedQuery, e);
            }
        }
        try {
            List<StockInfo> stockInfoList = stockInfoDao.getStockInfoByName(normalizedQuery, 10, 0);
            for (StockInfo stockInfo : stockInfoList) {
                DomesticStockInfoSimpleItem.Builder itemBuilder = DomesticStockInfoSimpleItem.newBuilder();
                itemBuilder.setTsCode(stockInfo.getTsCode())
                        .setSymbol(stockInfo.getSymbol())
                        .setName(stockInfo.getName())
                        .setArea(stockInfo.getArea() != null ? stockInfo.getArea() : "")
                        .setIndustry(stockInfo.getIndustry() != null ? stockInfo.getIndustry() : "");
                responseBuilder.addItems(itemBuilder.build());
            }
        } catch (Exception e) {
            log.error("DB fallback failed for stock search query={}", normalizedQuery, e);
        }
        return responseBuilder.build();
    }


    @Override
    public DomesticStockSearchESResponse searchStockES(DomesticStockSearchESRequest request) {
        String query = request.getQuery();
        if (query == null || query.trim().isEmpty()) {
            return DomesticStockSearchESResponse.newBuilder().build();
        }
        String normalizedQuery = query.trim();
        DomesticStockSearchESResponse.Builder responseBuilder = DomesticStockSearchESResponse.newBuilder();
        if (isMeiliEnabled()) {
            try {
                Index index = getMeiliClient().index("stocks");
                SearchResult searchResult = (SearchResult) index.search(
                        SearchRequest.builder().q(normalizedQuery).limit(20).build());
                for (Object hitObj : searchResult.getHits()) {
                    if (!(hitObj instanceof Map<?, ?> hit)) {
                        continue;
                    }
                    DomesticStockInfoESItem.Builder itemBuilder = DomesticStockInfoESItem.newBuilder();
                    // 将 MeiliSearch 的 ts_code 转换回原始格式（将 _ 替换为 .）
                    String meiliTsCode = stringValue(hit.get("ts_code"));
                    String originalTsCode = MeiliSearchDataSyncService.fromMeiliId(meiliTsCode);
                    itemBuilder.setTsCode(originalTsCode)
                            .setSymbol(stringValue(hit.get("symbol")))
                            .setName(stringValue(hit.get("name")))
                            .setArea(stringValue(hit.get("area")))
                            .setIndustry(stringValue(hit.get("industry")));
                    String enName = stringValue(hit.get("en_name"));
                    if (!enName.isEmpty()) {
                        itemBuilder.setEnName(enName);
                    }
                    String fullName = stringValue(hit.get("full_name"));
                    if (fullName.isEmpty()) {
                        fullName = stringValue(hit.get("fullname"));
                    }
                    if (!fullName.isEmpty()) {
                        itemBuilder.setFullName(fullName);
                    }
                    responseBuilder.addItems(itemBuilder.build());
                }
                if (responseBuilder.getItemsCount() > 0) {
                    return responseBuilder.build();
                }
            } catch (Exception e) {
                log.warn("MeiliSearch query failed for stock advanced search query={}", normalizedQuery, e);
            }
        }
        return searchStockESFromDb(normalizedQuery);
    }

    private DomesticStockSearchESResponse searchStockESFromDb(String query) {
        DomesticStockSearchESResponse.Builder responseBuilder = DomesticStockSearchESResponse.newBuilder();
        try {
            List<StockInfo> stockInfoList = stockInfoDao.getStockInfoByName(query, 20, 0);
            for (StockInfo stockInfo : stockInfoList) {
                DomesticStockInfoESItem.Builder itemBuilder = DomesticStockInfoESItem.newBuilder()
                        .setTsCode(stockInfo.getTsCode() != null ? stockInfo.getTsCode() : "")
                        .setSymbol(stockInfo.getSymbol() != null ? stockInfo.getSymbol() : "")
                        .setName(stockInfo.getName() != null ? stockInfo.getName() : "")
                        .setArea(stockInfo.getArea() != null ? stockInfo.getArea() : "")
                        .setIndustry(stockInfo.getIndustry() != null ? stockInfo.getIndustry() : "");
                if (stockInfo.getEnName() != null) {
                    itemBuilder.setEnName(stockInfo.getEnName());
                }
                if (stockInfo.getFullName() != null) {
                    itemBuilder.setFullName(stockInfo.getFullName());
                }
                responseBuilder.addItems(itemBuilder.build());
            }
        } catch (Exception e) {
            log.error("DB fallback failed for stock advanced search query={}", query, e);
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
                    log.info("MeiliSearch client refreshed for stock service, host={}", host);
                }
            }
        }
        return localClient;
    }


    @Override
    public DomesticStockTsCodeResponse getStockTsCode(DomesticStockTsCodeRequest request) {
        int offset = request.getOffset();
        int limit = request.getLimit();

        List<String> stockTsCodeList = stockInfoDao.getStockTsCode(offset, limit);

        DomesticStockTsCodeResponse.Builder responseBuilder = DomesticStockTsCodeResponse.newBuilder();

        for (String tsCode : stockTsCodeList) {
            responseBuilder.addTsCodes(tsCode);
        }

        return responseBuilder.build();
    }



    @Override
    public DomesticStockDailyByTsCodeAndDateRangeResponse getStockDailyByTsCodeAndDateRange(DomesticStockDailyByTsCodeAndDateRangeRequest request) {
        String tsCode = request.getTsCode();
        long startDate = request.getStartDate();
        long endDate = request.getEndDate();

        List<StockDaily> stockDailyList = stockQuoteDao.getStockDailyByTsCodeAndDateRange(tsCode, startDate, endDate);

        if (stockDailyList == null) {
            log.warn("StockDaily list is null for tsCode: {}, startDate: {}, endDate: {}", tsCode, startDate, endDate);
            return DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder().build();
        }

        DomesticStockDailyByTsCodeAndDateRangeResponse.Builder responseBuilder = DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder();

        for (StockDaily stockDaily : stockDailyList) {
            DomesticStockDailyItem.Builder itemBuilder = DomesticStockDailyItem.newBuilder();
            itemBuilder.setStockDailyId(-1)
                    .setTsCode(stockDaily.getTsCode())
                    .setTradeDate(stockDaily.getTradeDate())
                    .setClose(stockDaily.getClose())
                    .setOpen(stockDaily.getOpen())
                    .setHigh(stockDaily.getHigh())
                    .setLow(stockDaily.getLow())
                    .setPreClose(stockDaily.getPreClose())
                    .setChange(stockDaily.getChange())
                    .setPctChg(stockDaily.getPctChg())
                    .setVol(stockDaily.getVol())
                    .setAmount(stockDaily.getAmount());

            responseBuilder.addItems(itemBuilder.build());
        }

        return responseBuilder.build();
    }

    @Override
    public DomesticStockDailyByTradeDateResponse getStockDailyByTradeDate(DomesticStockDailyByTradeDateRequest request) {
        long tradeDate = request.getTradeDate();

        List<StockDaily> stockDailyList = stockQuoteDao.getStockDailyByTradeDate(tradeDate);

        DomesticStockDailyByTradeDateResponse.Builder responseBuilder = DomesticStockDailyByTradeDateResponse.newBuilder();

        for (StockDaily stockDaily : stockDailyList) {
            DomesticStockDailyItem.Builder itemBuilder = DomesticStockDailyItem.newBuilder();
            itemBuilder.setStockDailyId(-1)
                    .setTsCode(stockDaily.getTsCode())
                    .setTradeDate(stockDaily.getTradeDate())
                    .setClose(stockDaily.getClose())
                    .setOpen(stockDaily.getOpen())
                    .setHigh(stockDaily.getHigh())
                    .setLow(stockDaily.getLow())
                    .setPreClose(stockDaily.getPreClose())
                    .setChange(stockDaily.getChange())
                    .setPctChg(stockDaily.getPctChg())
                    .setVol(stockDaily.getVol())
                    .setAmount(stockDaily.getAmount());

            responseBuilder.addItems(itemBuilder.build());
        }

        return responseBuilder.build();
    }
}

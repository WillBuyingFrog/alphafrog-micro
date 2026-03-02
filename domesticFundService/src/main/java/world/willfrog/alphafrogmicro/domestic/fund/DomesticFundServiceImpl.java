package world.willfrog.alphafrogmicro.domestic.fund;

import lombok.extern.slf4j.Slf4j;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.model.SearchResult;
import org.springframework.stereotype.Service;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import world.willfrog.alphafrogmicro.common.component.MeiliSearchIndexManager;
import world.willfrog.alphafrogmicro.common.component.MeiliSearchDataSyncService;
import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundNavDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundPortfolioDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundInfo;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundNav;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundPortfolio;
import world.willfrog.alphafrogmicro.domestic.idl.*;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticFundServiceTriple.DomesticFundServiceImplBase;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@DubboService
@Service
@Slf4j
public class DomesticFundServiceImpl extends DomesticFundServiceImplBase {

    private static final String MEILI_HOST_PROP = "meilisearch.host";
    private static final String MEILI_API_KEY_PROP = "meilisearch.api-key";
    private static final String MEILI_ENABLED_PROP = "advanced.meili-enabled";
    private static final String MEILI_AUTO_SYNC_PROP = "advanced.meili-auto-sync";
    private static final String DEFAULT_MEILI_HOST = "http://localhost:7700";
    private static final String DEFAULT_MEILI_API_KEY = "alphafrog_search_key";

    private final FundNavDao fundNavDao;
    private final FundInfoDao fundInfoDao;
    private final FundPortfolioDao fundPortfolioDao;
    private final Environment environment;
    private volatile Client meiliClient;
    private volatile String meiliClientHost;
    private volatile String meiliClientApiKey;
    
    // MeiliSearch 索引管理
    private volatile MeiliSearchIndexManager indexManager;
    private volatile MeiliSearchDataSyncService syncService;

    public DomesticFundServiceImpl(FundNavDao fundNavDao, FundInfoDao fundInfoDao,
                                   FundPortfolioDao fundPortfolioDao, Environment environment) {
        this.fundNavDao = fundNavDao;
        this.fundInfoDao = fundInfoDao;
        this.fundPortfolioDao = fundPortfolioDao;
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
                "funds",
                new String[]{"name", "ts_code", "management", "fund_type"}, // 可搜索字段
                new String[]{"fund_type", "market"}, // 可过滤字段
                new String[]{"name", "ts_code"} // 可排序字段
            );
            
            // 初始化索引（创建 + 配置）
            boolean initialized = indexManager.initializeIndex();
            if (initialized) {
                log.info("MeiliSearch funds 索引初始化成功");
                
                // 创建同步服务
                syncService = new MeiliSearchDataSyncService(client, "funds", 500);
                
                // 检查是否需要自动同步
                if (isAutoSyncEnabled()) {
                    // 异步触发全量同步
                    triggerFullSync();
                } else {
                    log.info("MeiliSearch 自动同步已禁用，跳过数据导入");
                }
            } else {
                log.error("MeiliSearch funds 索引初始化失败");
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

        // 获取基金总数
        int totalCount = fundInfoDao.getFundInfoCount();
        
        // 定义数据获取函数
        MeiliSearchDataSyncService.FetchFunction<FundInfo> fetchFunction = 
            (offset, limit) -> fundInfoDao.getAllFundInfo(offset, limit);
        
        // 定义文档转换函数
        Function<FundInfo, Map<String, Object>> docConverter = this::convertToMeiliDocument;
        
        // 异步执行同步
        syncService.asyncFullSync(fetchFunction, docConverter, totalCount)
            .thenAccept(result -> {
                if (result.isSuccess()) {
                    log.info("[funds] MeiliSearch 同步完成: {}", result.getMessage());
                } else {
                    log.error("[funds] MeiliSearch 同步失败: {}", result.getErrorMessage());
                }
            });
    }

    /**
     * 定时全量同步（每天凌晨 4:00）
     */
    @Scheduled(cron = "${advanced.meili-sync-cron-funds:0 0 4 * * ?}")
    public void scheduledFullSync() {
        if (!isMeiliEnabled() || !isAutoSyncEnabled()) {
            return;
        }
        
        log.info("[funds] 定时同步任务触发");
        triggerFullSync();
    }

    /**
     * 将 FundInfo 转换为 MeiliSearch 文档
     * 注意：ts_code 需要转换为 MeiliSearch 合法的文档 ID（将 . 替换为 _）
     */
    private Map<String, Object> convertToMeiliDocument(FundInfo fund) {
        Map<String, Object> doc = new HashMap<>();
        // MeiliSearch 文档 ID 不能包含 '.'，需要转换
        doc.put("ts_code", MeiliSearchDataSyncService.toMeiliId(fund.getTsCode()));
        doc.put("name", fund.getName());
        doc.put("management", fund.getManagement());
        doc.put("fund_type", fund.getFundType());
        doc.put("market", fund.getMarket());
        doc.put("benchmark", fund.getBenchmark());
        return doc;
    }

    @Override
    public DomesticFundNavsByTsCodeAndDateRangeResponse getDomesticFundNavsByTsCodeAndDateRange(
            DomesticFundNavsByTsCodeAndDateRangeRequest request
    ) {
        String tsCode = request.getTsCode();
        long startDateTimestamp = request.getStartDateTimestamp();
        long endDateTimestamp = request.getEndDateTimestamp();

        List<FundNav> fundNavList = null;

        try{
            fundNavList = fundNavDao.getFundNavsByTsCodeAndDateRange(tsCode,
                                            startDateTimestamp, endDateTimestamp);
        } catch (Exception e) {
            log.error("Error occurred while getting fund navs for tsCode: {}, dateRange: {}-{}", 
                     tsCode, startDateTimestamp, endDateTimestamp, e);
            // 返回空响应而不是null，避免客户端NPE
            return DomesticFundNavsByTsCodeAndDateRangeResponse.newBuilder().build();
        }

        // 创建响应对象
        DomesticFundNavsByTsCodeAndDateRangeResponse.Builder responseBuilder =
                DomesticFundNavsByTsCodeAndDateRangeResponse.newBuilder();

        if(fundNavList != null) {
            try {
                for (FundNav fundNav : fundNavList) {
                    DomesticFundNavItem.Builder itemBuilder = DomesticFundNavItem.newBuilder()
                            .setTsCode(fundNav.getTsCode()).setAnnDate(fundNav.getAnnDate())
                            .setNavDate(fundNav.getNavDate()).setUnitNav(fundNav.getUnitNav())
                            .setAdjNav(fundNav.getAdjNav());

                    if(fundNav.getAccumNav() != null){
                        itemBuilder.setAccumNav(fundNav.getAccumNav());
                    }
                    if(fundNav.getNetAsset() != null){
                        itemBuilder.setNetAsset(fundNav.getNetAsset());
                    }
                    if(fundNav.getTotalNetAsset() != null) {
                        itemBuilder.setTotalNetAsset(fundNav.getTotalNetAsset());
                    }
                    if(fundNav.getAccumDiv() != null) {
                        itemBuilder.setAccumDiv(fundNav.getAccumDiv());
                    }
                    responseBuilder.addItems(itemBuilder.build());
                }
                return responseBuilder.build();
            } catch (Exception e) {
                log.error("Error occurred while converting fund nav data to protobuf for tsCode: {}", tsCode, e);
                // 转换错误时返回空响应
                return DomesticFundNavsByTsCodeAndDateRangeResponse.newBuilder().build();
            }
        } else {
            // 数据为空时记录日志并返回空响应
            log.warn("Fund nav data not found for tsCode: {}, dateRange: {}-{}", 
                    tsCode, startDateTimestamp, endDateTimestamp);
            return DomesticFundNavsByTsCodeAndDateRangeResponse.newBuilder().build();
        }
    }

    @Override
    public DomesticFundInfoByTsCodeResponse getDomesticFundInfoByTsCode(DomesticFundInfoByTsCodeRequest request) {

        String tsCode = request.getTsCode();
        List<FundInfo> fundInfoList;
        List<DomesticFundInfoFullItem> items = new ArrayList<>();

        try {
            // 使用合理的分页参数，避免返回过多数据
            fundInfoList = fundInfoDao.getFundInfoByTsCode(tsCode, 100, 0);

            for(FundInfo fundInfo : fundInfoList) {
                log.debug("FundInfo: {}", fundInfo);
                DomesticFundInfoFullItem.Builder itemBuilder = DomesticFundInfoFullItem.newBuilder()
                        .setTsCode(fundInfo.getTsCode()).setName(fundInfo.getName());

                // 设置optional字段，并检查是否为null
                if (fundInfo.getManagement() != null) {
                    itemBuilder.setManagement(fundInfo.getManagement());
                }
                if (fundInfo.getCustodian() != null) {
                    itemBuilder.setCustodian(fundInfo.getCustodian());
                }
                if (fundInfo.getFundType() != null) {
                    itemBuilder.setFundType(fundInfo.getFundType());
                }
                if (fundInfo.getFoundDate() != null) {
                    itemBuilder.setFoundDate(fundInfo.getFoundDate());
                }
                if (fundInfo.getDueDate() != null) {
                    itemBuilder.setDueDate(fundInfo.getDueDate());
                }
                if (fundInfo.getListDate() != null) {
                    itemBuilder.setListDate(fundInfo.getListDate());
                }
                if (fundInfo.getIssueDate() != null) {
                    itemBuilder.setIssueDate(fundInfo.getIssueDate());
                }
                if (fundInfo.getDelistDate() != null) {
                    itemBuilder.setDelistDate(fundInfo.getDelistDate());
                }
                if (fundInfo.getIssueAmount() != null) {
                    itemBuilder.setIssueAmount(fundInfo.getIssueAmount());
                }
                if (fundInfo.getMFee() != null) {
                    itemBuilder.setMFee(fundInfo.getMFee());
                }
                if (fundInfo.getCFee() != null) {
                    itemBuilder.setCFee(fundInfo.getCFee());
                }
                if (fundInfo.getDurationYear() != null) {
                    itemBuilder.setDurationYear(fundInfo.getDurationYear());
                }
                if (fundInfo.getPValue() != null) {
                    itemBuilder.setPValue(fundInfo.getPValue());
                }
                if (fundInfo.getMinAmount() != null) {
                    itemBuilder.setMinAmount(fundInfo.getMinAmount());
                }
                if (fundInfo.getExpReturn() != null) {
                    itemBuilder.setExpReturn(fundInfo.getExpReturn());
                }
                if (fundInfo.getBenchmark() != null) {
                    itemBuilder.setBenchmark(fundInfo.getBenchmark());
                }
                if (fundInfo.getStatus() != null) {
                    itemBuilder.setStatus(fundInfo.getStatus());
                }
                if (fundInfo.getInvestType() != null) {
                    itemBuilder.setInvestType(fundInfo.getInvestType());
                }
                if (fundInfo.getType() != null) {
                    itemBuilder.setType(fundInfo.getType());
                }
                if (fundInfo.getTrustee() != null) {
                    itemBuilder.setTrustee(fundInfo.getTrustee());
                }
                if (fundInfo.getPurcStartDate() != null) {
                    itemBuilder.setPurcStartDate(fundInfo.getPurcStartDate());
                }
                if (fundInfo.getRedmStartDate() != null) {
                    itemBuilder.setRedmStartDate(fundInfo.getRedmStartDate());
                }
                if (fundInfo.getMarket() != null) {
                    itemBuilder.setMarket(fundInfo.getMarket());
                }

                items.add(itemBuilder.build());
            }

            return DomesticFundInfoByTsCodeResponse.newBuilder()
                    .addAllItems(items).build();

        } catch (Exception e) {
            log.error("Error occurred while getting fund info for tsCode: {}", tsCode, e);
            // 返回空响应而不是null，避免客户端NPE
            return DomesticFundInfoByTsCodeResponse.newBuilder().build();
        }
    }

    @Override
    public DomesticFundSearchResponse searchDomesticFundInfo(DomesticFundSearchRequest request) {
        
        String query = request.getQuery();
        if (query == null || query.trim().isEmpty()) {
            return DomesticFundSearchResponse.newBuilder().build();
        }
        String normalizedQuery = query.trim();
        List<DomesticFundInfoSimpleItem> items = new ArrayList<>();
        if (isMeiliEnabled()) {
            try {
                Index index = getMeiliClient().index("funds");
                SearchResult searchResult = (SearchResult) index.search(
                        SearchRequest.builder().q(normalizedQuery).limit(100).build());
                for (Object hitObj : searchResult.getHits()) {
                    if (!(hitObj instanceof Map<?, ?> hit)) {
                        continue;
                    }
                    // 将 MeiliSearch 的 ts_code 转换回原始格式（将 _ 替换为 .）
                    String meiliTsCode = stringValue(hit.get("ts_code"));
                    String originalTsCode = MeiliSearchDataSyncService.fromMeiliId(meiliTsCode);
                    DomesticFundInfoSimpleItem.Builder itemBuilder = DomesticFundInfoSimpleItem.newBuilder()
                            .setTsCode(originalTsCode)
                            .setName(stringValue(hit.get("name")));
                    String management = stringValue(hit.get("management"));
                    if (!management.isEmpty()) {
                        itemBuilder.setManagement(management);
                    }
                    String fundType = stringValue(hit.get("fund_type"));
                    if (!fundType.isEmpty()) {
                        itemBuilder.setFundType(fundType);
                    }
                    items.add(itemBuilder.build());
                }
                if (!items.isEmpty()) {
                    return DomesticFundSearchResponse.newBuilder()
                            .addAllItems(items)
                            .build();
                }
            } catch (Exception e) {
                log.warn("MeiliSearch query failed for fund search query={}", normalizedQuery, e);
            }
        }
        List<FundInfo> fundInfoList = new ArrayList<>();

        try{
            // 使用合理的分页参数，避免返回过多数据
            fundInfoList = fundInfoDao.getFundInfoByTsCode(normalizedQuery, 50, 0);
            fundInfoList.addAll(fundInfoDao.getFundInfoByName(normalizedQuery, 50, 0));
            // 去重
            fundInfoList = fundInfoList.stream()
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.error("Error occurred while searching fund info with query: {}", normalizedQuery, e);
            // 搜索异常时返回空响应而不是null
            return DomesticFundSearchResponse.newBuilder().build();
        }
        

        try {
            for (FundInfo fundInfo : fundInfoList) {
                DomesticFundInfoSimpleItem.Builder itemBuilder = DomesticFundInfoSimpleItem.newBuilder()
                        .setTsCode(fundInfo.getTsCode())
                        .setName(fundInfo.getName());
                
                if (fundInfo.getManagement() != null) {
                    itemBuilder.setManagement(fundInfo.getManagement());
                }

                if (fundInfo.getFundType() != null) {
                    itemBuilder.setFundType(fundInfo.getFundType());
                }

                if (fundInfo.getFoundDate() != null) {
                    itemBuilder.setFoundDate(fundInfo.getFoundDate());
                }

                if (fundInfo.getBenchmark() != null) {
                    itemBuilder.setBenchmark(fundInfo.getBenchmark());
                }

                items.add(itemBuilder.build());
            }

            return DomesticFundSearchResponse.newBuilder()
                    .addAllItems(items)
                    .build();
        } catch (Exception e) {
            log.error("Error occurred while converting fund search data to protobuf for query: {}", normalizedQuery, e);
            // 转换错误时返回空响应
            return DomesticFundSearchResponse.newBuilder().build();
        }
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
                    log.info("MeiliSearch client refreshed for fund service, host={}", host);
                }
            }
        }
        return localClient;
    }

    public DomesticFundPortfolioByTsCodeAndDateRangeResponse getDomesticFundPortfolioByTsCodeAndDateRange(
            DomesticFundPortfolioByTsCodeAndDateRangeRequest request
    ) {
        String tsCode = request.getTsCode();
        long startDateTimestamp = request.getStartDateTimestamp();
        long endDateTimestamp = request.getEndDateTimestamp();

        List<FundPortfolio> fundPortfolioList = null;

        try{
            fundPortfolioList = fundPortfolioDao.getFundPortfolioByTsCodeAndDateRange(tsCode,
                    startDateTimestamp, endDateTimestamp);
        } catch (Exception e) {
            log.error("Error occurred while getting fund portfolio for tsCode: {}, dateRange: {}-{}", 
                     tsCode, startDateTimestamp, endDateTimestamp, e);
            // 返回空响应而不是null
            return DomesticFundPortfolioByTsCodeAndDateRangeResponse.newBuilder().build();
        }

        // 创建响应对象
        DomesticFundPortfolioByTsCodeAndDateRangeResponse.Builder responseBuilder =
                DomesticFundPortfolioByTsCodeAndDateRangeResponse.newBuilder();

        if(fundPortfolioList != null) {
            try {
                for (FundPortfolio fundPortfolio : fundPortfolioList) {
                    DomesticFundPortfolioItem.Builder itemBuilder = DomesticFundPortfolioItem.newBuilder()
                            .setTsCode(fundPortfolio.getTsCode()).setAnnDate(fundPortfolio.getAnnDate())
                            .setEndDate(fundPortfolio.getEndDate()).setSymbol(fundPortfolio.getSymbol())
                            .setMkv(fundPortfolio.getMkv()).setAmount(fundPortfolio.getAmount());

                    if (fundPortfolio.getStkMkvRatio() != null) {
                        itemBuilder.setSktMkvRatio(fundPortfolio.getStkMkvRatio());
                    }

                    if (fundPortfolio.getStkFloatRatio() != null) {
                        itemBuilder.setSktFloatRatio(fundPortfolio.getStkFloatRatio());
                    }


                    responseBuilder.addItems(itemBuilder.build());
                }
                return responseBuilder.build();
            } catch (Exception e) {
                log.error("Error occurred while converting fund portfolio data to protobuf for tsCode: {}", tsCode, e);
                // 转换错误时返回空响应
                return DomesticFundPortfolioByTsCodeAndDateRangeResponse.newBuilder().build();
            }
        } else {
            // 数据为空时记录日志并返回空响应
            log.warn("Fund portfolio data not found for tsCode: {}, dateRange: {}-{}", 
                    tsCode, startDateTimestamp, endDateTimestamp);
            return DomesticFundPortfolioByTsCodeAndDateRangeResponse.newBuilder().build();
        }
    }

    @Override
    public DomesticFundPortfolioBySymbolAndDateRangeResponse getDomesticFundPortfolioBySymbolAndDateRange(
            DomesticFundPortfolioBySymbolAndDateRangeRequest request
    ) {
        String symbol = request.getSymbol();
        long startDateTimestamp = request.getStartDateTimestamp();
        long endDateTimestamp = request.getEndDateTimestamp();

        List<FundPortfolio> fundPortfolioList = null;

        try{
            fundPortfolioList = fundPortfolioDao.getFundPortfolioBySymbolAndDateRange(symbol,
                    startDateTimestamp, endDateTimestamp);
        } catch (Exception e) {
            log.error("Error occurred while getting fund portfolio for symbol: {}, dateRange: {}-{}", 
                     symbol, startDateTimestamp, endDateTimestamp, e);
            // 返回空响应而不是null
            return DomesticFundPortfolioBySymbolAndDateRangeResponse.newBuilder().build();
        }

        // 创建响应对象
        DomesticFundPortfolioBySymbolAndDateRangeResponse.Builder responseBuilder =
                DomesticFundPortfolioBySymbolAndDateRangeResponse.newBuilder();

        if(fundPortfolioList != null) {
            try {
                for (FundPortfolio fundPortfolio : fundPortfolioList) {
                    DomesticFundPortfolioItem.Builder itemBuilder = DomesticFundPortfolioItem.newBuilder()
                            .setTsCode(fundPortfolio.getTsCode()).setAnnDate(fundPortfolio.getAnnDate())
                            .setEndDate(fundPortfolio.getEndDate()).setSymbol(fundPortfolio.getSymbol())
                            .setMkv(fundPortfolio.getMkv()).setAmount(fundPortfolio.getAmount());

                    if (fundPortfolio.getStkMkvRatio() != null) {
                        itemBuilder.setSktMkvRatio(fundPortfolio.getStkMkvRatio());
                    }

                    if (fundPortfolio.getStkFloatRatio() != null) {
                        itemBuilder.setSktFloatRatio(fundPortfolio.getStkFloatRatio());
                    }

                    responseBuilder.addItems(itemBuilder.build());
                }
                return responseBuilder.build();
            } catch (Exception e) {
                log.error("Error occurred while converting fund portfolio data to protobuf for symbol: {}", symbol, e);
                // 转换错误时返回空响应
                return DomesticFundPortfolioBySymbolAndDateRangeResponse.newBuilder().build();
            }
        } else {
            // 数据为空时记录日志并返回空响应
            log.warn("Fund portfolio data not found for symbol: {}, dateRange: {}-{}", 
                    symbol, startDateTimestamp, endDateTimestamp);
            return DomesticFundPortfolioBySymbolAndDateRangeResponse.newBuilder().build();
        }
    }

}

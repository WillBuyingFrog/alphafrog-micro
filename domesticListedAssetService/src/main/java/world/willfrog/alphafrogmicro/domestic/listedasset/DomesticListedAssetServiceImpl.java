package world.willfrog.alphafrogmicro.domestic.listedasset;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.model.SearchResult;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.component.MeiliSearchDataSyncService;
import world.willfrog.alphafrogmicro.common.component.MeiliSearchIndexManager;
import world.willfrog.alphafrogmicro.common.dao.domestic.etf.EtfAdjFactorDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.etf.EtfDailyDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.etf.EtfInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockQuoteDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.etf.EtfAdjFactor;
import world.willfrog.alphafrogmicro.common.pojo.domestic.etf.EtfDaily;
import world.willfrog.alphafrogmicro.common.pojo.domestic.etf.EtfInfo;
import world.willfrog.alphafrogmicro.common.pojo.domestic.quote.Quote;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockDaily;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockInfo;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticListedAssetServiceTriple.DomesticListedAssetServiceImplBase;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetAdjFactorItem;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetAdjFactorRequest;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetAdjFactorResponse;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetDailyItem;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetDailyRequest;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetDailyResponse;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetInfoItem;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetInfoRequest;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetInfoResponse;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetSearchRequest;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetSearchResponse;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

@DubboService
@Service
@Slf4j
public class DomesticListedAssetServiceImpl extends DomesticListedAssetServiceImplBase {

    private static final String ASSET_TYPE_STOCK = "stock";
    private static final String ASSET_TYPE_ETF = "etf";
    private static final int DEFAULT_SEARCH_LIMIT = 20;
    private static final int MAX_SEARCH_LIMIT = 100;
    private static final String MEILI_ETF_INDEX = "etfs";
    private static final String MEILI_HOST_PROP = "meilisearch.host";
    private static final String MEILI_API_KEY_PROP = "meilisearch.api-key";
    private static final String MEILI_ENABLED_PROP = "advanced.meili-enabled";
    private static final String MEILI_AUTO_SYNC_PROP = "advanced.meili-auto-sync";
    private static final String DEFAULT_MEILI_HOST = "http://localhost:7700";
    private static final String DEFAULT_MEILI_API_KEY = "alphafrog_search_key";

    private final StockInfoDao stockInfoDao;
    private final StockQuoteDao stockQuoteDao;
    private final EtfInfoDao etfInfoDao;
    private final EtfDailyDao etfDailyDao;
    private final EtfAdjFactorDao etfAdjFactorDao;
    private final Environment environment;
    private volatile Client meiliClient;
    private volatile String meiliClientHost;
    private volatile String meiliClientApiKey;
    private volatile MeiliSearchIndexManager etfIndexManager;
    private volatile MeiliSearchDataSyncService etfSyncService;

    public DomesticListedAssetServiceImpl(StockInfoDao stockInfoDao,
                                          StockQuoteDao stockQuoteDao,
                                          EtfInfoDao etfInfoDao,
                                          EtfDailyDao etfDailyDao,
                                          EtfAdjFactorDao etfAdjFactorDao,
                                          Environment environment) {
        this.stockInfoDao = stockInfoDao;
        this.stockQuoteDao = stockQuoteDao;
        this.etfInfoDao = etfInfoDao;
        this.etfDailyDao = etfDailyDao;
        this.etfAdjFactorDao = etfAdjFactorDao;
        this.environment = environment;
    }

    @PostConstruct
    public void initMeiliEtfIndex() {
        if (!isMeiliEnabled()) {
            log.info("MeiliSearch 已禁用，跳过 ETF 索引初始化");
            return;
        }
        try {
            Client client = getMeiliClient();
            etfIndexManager = new MeiliSearchIndexManager(
                    client,
                    MEILI_ETF_INDEX,
                    new String[]{"name", "ts_code", "full_name", "index_code", "index_name", "mgr_name", "etf_type"},
                    new String[]{"exchange", "etf_type", "list_status"},
                    new String[]{"name", "ts_code"}
            );
            if (etfIndexManager.initializeIndex()) {
                log.info("MeiliSearch {} 索引初始化成功", MEILI_ETF_INDEX);
                etfSyncService = new MeiliSearchDataSyncService(client, MEILI_ETF_INDEX, 500);
                if (isAutoSyncEnabled()) {
                    triggerEtfFullSync();
                } else {
                    log.info("MeiliSearch ETF 自动同步已禁用，跳过数据导入");
                }
            } else {
                log.error("MeiliSearch {} 索引初始化失败", MEILI_ETF_INDEX);
            }
        } catch (Exception e) {
            log.error("初始化 MeiliSearch ETF 索引失败: {}", e.getMessage(), e);
        }
    }

    private void triggerEtfFullSync() {
        if (etfSyncService == null || etfSyncService.isSyncing()) {
            return;
        }
        int totalCount = etfInfoDao.getEtfInfoCount();
        MeiliSearchDataSyncService.FetchFunction<EtfInfo> fetchFunction =
                (offset, limit) -> etfInfoDao.getAllEtfInfo(offset, limit);
        Function<EtfInfo, Map<String, Object>> docConverter = this::convertEtfToMeiliDocument;
        etfSyncService.asyncFullSync(fetchFunction, docConverter, totalCount)
                .thenAccept(result -> {
                    if (result.isSuccess()) {
                        log.info("[{}] MeiliSearch 同步完成: {}", MEILI_ETF_INDEX, result.getMessage());
                    } else {
                        log.error("[{}] MeiliSearch 同步失败: {}", MEILI_ETF_INDEX, result.getErrorMessage());
                    }
                });
    }

    @Scheduled(cron = "${advanced.meili-sync-cron-etfs:0 45 3 * * ?}")
    public void scheduledEtfFullSync() {
        if (!isMeiliEnabled() || !isAutoSyncEnabled()) {
            return;
        }
        log.info("[{}] 定时同步任务触发", MEILI_ETF_INDEX);
        triggerEtfFullSync();
    }

    private Map<String, Object> convertEtfToMeiliDocument(EtfInfo etf) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("ts_code", MeiliSearchDataSyncService.toMeiliId(etf.getTsCode()));
        doc.put("name", etf.getName());
        doc.put("full_name", etf.getFullName());
        doc.put("exchange", etf.getExchange());
        doc.put("etf_type", etf.getEtfType());
        doc.put("index_code", etf.getIndexCode());
        doc.put("index_name", etf.getIndexName());
        doc.put("mgr_name", etf.getMgrName());
        doc.put("list_status", etf.getListStatus());
        return doc;
    }

    @Override
    public ListedAssetInfoResponse getListedAssetInfo(ListedAssetInfoRequest request) {
        String tsCode = trimToEmpty(request.getTsCode());
        String assetType = normalizeAssetType(request.getAssetType());
        if (tsCode.isEmpty()) {
            return ListedAssetInfoResponse.newBuilder().build();
        }
        if (assetType.isEmpty()) {
            assetType = inferAssetType(tsCode);
        }

        ListedAssetInfoItem item = null;
        if (ASSET_TYPE_STOCK.equals(assetType)) {
            item = findStockInfo(tsCode);
        } else if (ASSET_TYPE_ETF.equals(assetType)) {
            item = findEtfInfo(tsCode);
        }

        ListedAssetInfoResponse.Builder response = ListedAssetInfoResponse.newBuilder();
        if (item != null) {
            response.setItem(item);
        }
        return response.build();
    }

    @Override
    public ListedAssetSearchResponse searchListedAssets(ListedAssetSearchRequest request) {
        String query = trimToEmpty(request.getQuery());
        if (query.isEmpty()) {
            return ListedAssetSearchResponse.newBuilder().build();
        }

        Set<String> assetTypes = normalizeAssetTypes(request.getAssetTypesList());
        if (assetTypes.isEmpty()) {
            assetTypes.add(ASSET_TYPE_STOCK);
            assetTypes.add(ASSET_TYPE_ETF);
        }
        int limit = normalizeLimit(request.hasLimit() ? request.getLimit() : DEFAULT_SEARCH_LIMIT);
        int perTypeLimit = Math.max(1, limit);

        ListedAssetSearchResponse.Builder response = ListedAssetSearchResponse.newBuilder();
        if (assetTypes.contains(ASSET_TYPE_STOCK)) {
            addStockSearchResults(response, query, perTypeLimit);
        }
        if (assetTypes.contains(ASSET_TYPE_ETF)) {
            addEtfSearchResults(response, query, perTypeLimit);
        }
        return response.build();
    }

    @Override
    public ListedAssetDailyResponse getListedAssetDaily(ListedAssetDailyRequest request) {
        String tsCode = trimToEmpty(request.getTsCode());
        String assetType = normalizeAssetType(request.getAssetType());
        if (tsCode.isEmpty()) {
            return ListedAssetDailyResponse.newBuilder().build();
        }
        if (assetType.isEmpty()) {
            assetType = inferAssetType(tsCode);
        }

        ListedAssetDailyResponse.Builder response = ListedAssetDailyResponse.newBuilder();
        try {
            if (ASSET_TYPE_STOCK.equals(assetType)) {
                List<StockDaily> rows = stockQuoteDao.getStockDailyByTsCodeAndDateRange(
                        tsCode, request.getStartDate(), request.getEndDate());
                if (rows != null) {
                    rows.forEach(row -> response.addItems(toDailyItem(ASSET_TYPE_STOCK, row)));
                }
            } else if (ASSET_TYPE_ETF.equals(assetType)) {
                List<EtfDaily> rows = etfDailyDao.getByTsCodeAndDateRange(
                        tsCode, request.getStartDate(), request.getEndDate());
                if (rows != null) {
                    rows.forEach(row -> response.addItems(toDailyItem(ASSET_TYPE_ETF, row)));
                }
            }
        } catch (Exception e) {
            log.error("Error getting listed asset daily for tsCode={}, assetType={}", tsCode, assetType, e);
            return ListedAssetDailyResponse.newBuilder().build();
        }
        return response.build();
    }

    @Override
    public ListedAssetAdjFactorResponse getListedAssetAdjFactors(ListedAssetAdjFactorRequest request) {
        String tsCode = trimToEmpty(request.getTsCode());
        if (tsCode.isEmpty()) {
            return ListedAssetAdjFactorResponse.newBuilder().build();
        }

        ListedAssetAdjFactorResponse.Builder response = ListedAssetAdjFactorResponse.newBuilder();
        try {
            List<EtfAdjFactor> rows = etfAdjFactorDao.getByTsCodeAndDateRange(
                    tsCode, request.getStartDate(), request.getEndDate());
            if (rows != null) {
                for (EtfAdjFactor row : rows) {
                    ListedAssetAdjFactorItem.Builder item = ListedAssetAdjFactorItem.newBuilder()
                            .setTsCode(nullToEmpty(row.getTsCode()));
                    setLongIfPresent(item::setTradeDate, row.getTradeDate());
                    setDoubleIfPresent(item::setAdjFactor, row.getAdjFactor());
                    response.addItems(item.build());
                }
            }
        } catch (Exception e) {
            log.error("Error getting ETF adj factors for tsCode={}", tsCode, e);
            return ListedAssetAdjFactorResponse.newBuilder().build();
        }
        return response.build();
    }

    private ListedAssetInfoItem findStockInfo(String tsCode) {
        try {
            List<StockInfo> rows = stockInfoDao.getStockInfoByTsCode(tsCode, 1, 0);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            return toStockInfoItem(rows.get(0));
        } catch (Exception e) {
            log.error("Error getting stock info for tsCode={}", tsCode, e);
            return null;
        }
    }

    private ListedAssetInfoItem findEtfInfo(String tsCode) {
        try {
            EtfInfo row = etfInfoDao.getByTsCode(tsCode);
            return row == null ? null : toEtfInfoItem(row);
        } catch (Exception e) {
            log.error("Error getting ETF info for tsCode={}", tsCode, e);
            return null;
        }
    }

    private void addStockSearchResults(ListedAssetSearchResponse.Builder response, String query, int limit) {
        try {
            List<StockInfo> rows = stockInfoDao.getStockInfoByName(query, limit, 0);
            if (rows != null) {
                rows.forEach(row -> response.addItems(toStockInfoItem(row)));
            }
            if (looksLikeTsCode(query)) {
                List<StockInfo> codeRows = stockInfoDao.getStockInfoByTsCode(query, limit, 0);
                if (codeRows != null) {
                    codeRows.forEach(row -> response.addItems(toStockInfoItem(row)));
                }
            }
        } catch (Exception e) {
            log.error("Error searching stock info for query={}", query, e);
        }
    }

    private void addEtfSearchResults(ListedAssetSearchResponse.Builder response, String query, int limit) {
        LinkedHashSet<String> seenTsCodes = new LinkedHashSet<>();
        int normalizedLimit = normalizeLimit(limit);
        try {
            if (isMeiliEnabled()) {
                searchEtfViaMeili(response, query, normalizedLimit, seenTsCodes);
            }
            if (response.getItemsCount() < normalizedLimit) {
                int remaining = normalizedLimit - response.getItemsCount();
                List<EtfInfo> rows = etfInfoDao.searchByKeyword(query, remaining);
                if (rows != null) {
                    for (EtfInfo row : rows) {
                        if (appendEtfItemIfNew(response, seenTsCodes, row)) {
                            if (response.getItemsCount() >= normalizedLimit) {
                                break;
                            }
                        }
                    }
                }
            }
            if (response.getItemsCount() < normalizedLimit && looksLikeTsCode(query)) {
                int remaining = normalizedLimit - response.getItemsCount();
                EtfInfo exact = etfInfoDao.getByTsCode(query);
                if (exact != null) {
                    appendEtfItemIfNew(response, seenTsCodes, exact);
                }
                List<EtfInfo> indexRows = etfInfoDao.getByIndexCode(query, remaining);
                if (indexRows != null) {
                    for (EtfInfo row : indexRows) {
                        if (appendEtfItemIfNew(response, seenTsCodes, row)) {
                            if (response.getItemsCount() >= normalizedLimit) {
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error searching ETF info for query={}", query, e);
        }
    }

    private void searchEtfViaMeili(ListedAssetSearchResponse.Builder response,
                                   String query,
                                   int limit,
                                   LinkedHashSet<String> seenTsCodes) {
        try {
            Index index = getMeiliClient().index(MEILI_ETF_INDEX);
            SearchResult searchResult = (SearchResult) index.search(
                    SearchRequest.builder().q(query.trim()).limit(Math.min(limit, MAX_SEARCH_LIMIT)).build());
            for (Object hitObj : searchResult.getHits()) {
                if (!(hitObj instanceof Map<?, ?> hit)) {
                    continue;
                }
                String tsCode = MeiliSearchDataSyncService.fromMeiliId(stringValue(hit.get("ts_code")));
                if (tsCode.isBlank() || !seenTsCodes.add(tsCode)) {
                    continue;
                }
                response.addItems(toEtfInfoItemFromMeiliHit(tsCode, hit));
                if (response.getItemsCount() >= limit) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("MeiliSearch query failed for ETF search query={}", query, e);
        }
    }

    private boolean appendEtfItemIfNew(ListedAssetSearchResponse.Builder response,
                                       LinkedHashSet<String> seenTsCodes,
                                       EtfInfo row) {
        String tsCode = nullToEmpty(row.getTsCode());
        if (tsCode.isBlank() || !seenTsCodes.add(tsCode)) {
            return false;
        }
        response.addItems(toEtfInfoItem(row));
        return true;
    }

    private ListedAssetInfoItem toEtfInfoItemFromMeiliHit(String tsCode, Map<?, ?> hit) {
        ListedAssetInfoItem.Builder item = ListedAssetInfoItem.newBuilder()
                .setAssetType(ASSET_TYPE_ETF)
                .setTsCode(tsCode)
                .setName(stringValue(hit.get("name")))
                .setSourceService("domesticListedAssetService");
        setStringIfPresent(item::setFullName, stringValue(hit.get("full_name")));
        setStringIfPresent(item::setExchange, stringValue(hit.get("exchange")));
        setStringIfPresent(item::setEtfType, stringValue(hit.get("etf_type")));
        setStringIfPresent(item::setIndexCode, stringValue(hit.get("index_code")));
        setStringIfPresent(item::setIndexName, stringValue(hit.get("index_name")));
        setStringIfPresent(item::setManagerName, stringValue(hit.get("mgr_name")));
        return item.build();
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
                    log.info("MeiliSearch client refreshed for listed asset service, host={}", host);
                }
            }
        }
        return localClient;
    }

    private ListedAssetInfoItem toStockInfoItem(StockInfo stock) {
        ListedAssetInfoItem.Builder item = ListedAssetInfoItem.newBuilder()
                .setAssetType(ASSET_TYPE_STOCK)
                .setTsCode(nullToEmpty(stock.getTsCode()))
                .setName(nullToEmpty(stock.getName()))
                .setSourceService("domesticStockService");
        setStringIfPresent(item::setSymbol, stock.getSymbol());
        setStringIfPresent(item::setFullName, stock.getFullName());
        setStringIfPresent(item::setExchange, stock.getExchange());
        setStringIfPresent(item::setMarket, stock.getMarket());
        setStringIfPresent(item::setIndustry, stock.getIndustry());
        setStringIfPresent(item::setListStatus, stock.getListStatus());
        setLongIfPresent(item::setListDate, stock.getListDate());
        setLongIfPresent(item::setDelistDate, stock.getDelistDate());
        return item.build();
    }

    private ListedAssetInfoItem toEtfInfoItem(EtfInfo etf) {
        ListedAssetInfoItem.Builder item = ListedAssetInfoItem.newBuilder()
                .setAssetType(ASSET_TYPE_ETF)
                .setTsCode(nullToEmpty(etf.getTsCode()))
                .setName(nullToEmpty(etf.getName()))
                .setSourceService("domesticListedAssetService");
        setStringIfPresent(item::setFullName, etf.getFullName());
        setStringIfPresent(item::setExchange, etf.getExchange());
        setStringIfPresent(item::setListStatus, etf.getListStatus());
        setStringIfPresent(item::setEtfType, etf.getEtfType());
        setStringIfPresent(item::setIndexCode, etf.getIndexCode());
        setStringIfPresent(item::setIndexName, etf.getIndexName());
        setStringIfPresent(item::setManagerName, etf.getMgrName());
        setLongIfPresent(item::setListDate, etf.getListDate());
        return item.build();
    }

    private ListedAssetDailyItem toDailyItem(String assetType, Quote quote) {
        ListedAssetDailyItem.Builder item = ListedAssetDailyItem.newBuilder()
                .setAssetType(assetType)
                .setTsCode(nullToEmpty(quote.getTsCode()));
        setLongIfPresent(item::setTradeDate, quote.getTradeDate());
        setDoubleIfPresent(item::setOpen, quote.getOpen());
        setDoubleIfPresent(item::setHigh, quote.getHigh());
        setDoubleIfPresent(item::setLow, quote.getLow());
        setDoubleIfPresent(item::setClose, quote.getClose());
        setDoubleIfPresent(item::setPreClose, quote.getPreClose());
        setDoubleIfPresent(item::setChange, quote.getChange());
        setDoubleIfPresent(item::setPctChg, quote.getPctChg());
        setDoubleIfPresent(item::setVol, quote.getVol());
        setDoubleIfPresent(item::setAmount, quote.getAmount());
        return item.build();
    }

    private Set<String> normalizeAssetTypes(List<String> assetTypes) {
        Set<String> result = new HashSet<>();
        if (assetTypes == null) {
            return result;
        }
        for (String assetType : assetTypes) {
            String normalized = normalizeAssetType(assetType);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private String normalizeAssetType(String assetType) {
        String normalized = trimToEmpty(assetType).toLowerCase(Locale.ROOT);
        if ("a_share".equals(normalized) || "listed_stock".equals(normalized)) {
            return ASSET_TYPE_STOCK;
        }
        if ("exchange_traded_fund".equals(normalized) || "listed_etf".equals(normalized)) {
            return ASSET_TYPE_ETF;
        }
        if (ASSET_TYPE_STOCK.equals(normalized) || ASSET_TYPE_ETF.equals(normalized)) {
            return normalized;
        }
        return "";
    }

    private String inferAssetType(String tsCode) {
        String normalized = tsCode.toUpperCase(Locale.ROOT);
        if (normalized.endsWith(".OF") || normalized.endsWith(".SH") || normalized.endsWith(".SZ")) {
            EtfInfo etf = etfInfoDao.getByTsCode(tsCode);
            if (etf != null) {
                return ASSET_TYPE_ETF;
            }
        }
        return ASSET_TYPE_STOCK;
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_SEARCH_LIMIT;
        }
        return Math.min(limit, MAX_SEARCH_LIMIT);
    }

    private boolean looksLikeTsCode(String query) {
        return query.indexOf('.') > 0 || query.chars().anyMatch(Character::isDigit);
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void setStringIfPresent(java.util.function.Consumer<String> setter, String value) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }

    private void setLongIfPresent(java.util.function.LongConsumer setter, Long value) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private void setDoubleIfPresent(java.util.function.DoubleConsumer setter, Double value) {
        if (value != null) {
            setter.accept(value);
        }
    }
}

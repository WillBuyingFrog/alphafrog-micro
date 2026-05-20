package world.willfrog.alphafrogmicro.domestic.listedasset;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
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

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@DubboService
@Service
@Slf4j
public class DomesticListedAssetServiceImpl extends DomesticListedAssetServiceImplBase {

    private static final String ASSET_TYPE_STOCK = "stock";
    private static final String ASSET_TYPE_ETF = "etf";
    private static final int DEFAULT_SEARCH_LIMIT = 20;
    private static final int MAX_SEARCH_LIMIT = 100;

    private final StockInfoDao stockInfoDao;
    private final StockQuoteDao stockQuoteDao;
    private final EtfInfoDao etfInfoDao;
    private final EtfDailyDao etfDailyDao;
    private final EtfAdjFactorDao etfAdjFactorDao;

    public DomesticListedAssetServiceImpl(StockInfoDao stockInfoDao,
                                          StockQuoteDao stockQuoteDao,
                                          EtfInfoDao etfInfoDao,
                                          EtfDailyDao etfDailyDao,
                                          EtfAdjFactorDao etfAdjFactorDao) {
        this.stockInfoDao = stockInfoDao;
        this.stockQuoteDao = stockQuoteDao;
        this.etfInfoDao = etfInfoDao;
        this.etfDailyDao = etfDailyDao;
        this.etfAdjFactorDao = etfAdjFactorDao;
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
        try {
            List<EtfInfo> rows = etfInfoDao.searchByKeyword(query, limit);
            if (rows != null) {
                rows.forEach(row -> response.addItems(toEtfInfoItem(row)));
            }
            List<EtfInfo> indexRows = etfInfoDao.getByIndexCode(query, limit);
            if (indexRows != null) {
                indexRows.forEach(row -> response.addItems(toEtfInfoItem(row)));
            }
        } catch (Exception e) {
            log.error("Error searching ETF info for query={}", query, e);
        }
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

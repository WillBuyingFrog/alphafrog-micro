package world.willfrog.alphafrogmicro.domestic.stock;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.StringQuery;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockQuoteDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockDaily;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockInfo;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStock.*;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticStockServiceTriple.*;
import world.willfrog.alphafrogmicro.domestic.stock.doc.StockInfoES;

import java.util.List;

@Service
@DubboService
@Slf4j
public class DomesticStockServiceImpl extends DomesticStockServiceImplBase {

    private final StockInfoDao stockInfoDao;
    private final StockQuoteDao stockQuoteDao;

    @Autowired(required = false)
    private final ElasticsearchOperations elasticsearchOperations;

    @Value("${advanced.es-enabled}")
    private boolean elasticsearchEnabled;

    public DomesticStockServiceImpl(StockInfoDao stockInfoDao,
                                    StockQuoteDao stockQuoteDao,
                                    ElasticsearchOperations elasticsearchOperations) {
        this.stockInfoDao = stockInfoDao;
        this.stockQuoteDao = stockQuoteDao;

        this.elasticsearchOperations = elasticsearchOperations;
    }

    @Override
    public DomesticStockInfoByTsCodeResponse getStockInfoByTsCode(DomesticStockInfoByTsCodeRequest request) {
        String tsCode = request.getTsCode();

        List<StockInfo> stockInfoList = stockInfoDao.getStockInfoByTsCode(tsCode);

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

        List<StockInfo> stockInfoList = stockInfoDao.getStockInfoByName(query);

        DomesticStockSearchResponse.Builder responseBuilder = DomesticStockSearchResponse.newBuilder();

        for (StockInfo stockInfo : stockInfoList) {
            DomesticStockInfoSimpleItem.Builder itemBuilder = DomesticStockInfoSimpleItem.newBuilder();
            itemBuilder.setTsCode(stockInfo.getTsCode())
                    .setSymbol(stockInfo.getSymbol())
                    .setName(stockInfo.getName())
                    .setArea(stockInfo.getArea() != null ? stockInfo.getArea() : "")
                    .setIndustry(stockInfo.getIndustry() != null ? stockInfo.getIndustry() : "");

            responseBuilder.addItems(itemBuilder.build());
        }

        return responseBuilder.build();
    }


    @Override
    public DomesticStockSearchESResponse searchStockES(DomesticStockSearchESRequest request) {

        if (elasticsearchOperations == null || !elasticsearchEnabled) {
            return DomesticStockSearchESResponse.newBuilder().build();
        }

        String query = request.getQuery();

        String queryString = String.format("{\"bool\": {\"should\": [{\"match\": {\"ts_code\": \"%s\"}}, {\"match\": {\"name\": \"%s\"}}, {\"match\": {\"fullname\": \"%s\"}}]}}", query, query, query);

        StringQuery stringQuery = new StringQuery(queryString);

        SearchHits<StockInfoES> searchHits = elasticsearchOperations.search(stringQuery, StockInfoES.class);

        DomesticStockSearchESResponse.Builder responseBuilder = DomesticStockSearchESResponse.newBuilder();

        searchHits.forEach(searchHit -> {
            StockInfoES stockInfoES = searchHit.getContent();

            DomesticStockInfoESItem.Builder itemBuilder = DomesticStockInfoESItem.newBuilder();
            itemBuilder.setTsCode(stockInfoES.getTsCode())
                    .setSymbol(stockInfoES.getSymbol())
                    .setName(stockInfoES.getName())
                    .setArea(stockInfoES.getArea() != null ? stockInfoES.getArea() : "")
                    .setIndustry(stockInfoES.getIndustry() != null ? stockInfoES.getIndustry() : "");

            responseBuilder.addItems(itemBuilder.build());
        });

        return responseBuilder.build();
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

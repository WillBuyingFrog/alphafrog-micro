package world.willfrog.alphafrogmicro.domestic.stock;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
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

    private final ElasticsearchOperations elasticsearchOperations;

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

        // 根据关键词查询股票信息
        List<StockInfo> stockInfoList = stockInfoDao.getStockInfoByName(query);

        // 构建响应对象
        DomesticStockSearchResponse.Builder responseBuilder = DomesticStockSearchResponse.newBuilder();

        // 将查询结果转换为简单信息对象
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
        String query = request.getQuery();

        String queryString = String.format("{\"bool\": {\"should\": [{\"match\": {\"ts_code\": \"%s\"}}, {\"match\": {\"name\": \"%s\"}}, {\"match\": {\"fullname\": \"%s\"}}]}}", query, query, query);

        StringQuery stringQuery = new StringQuery(queryString);

        SearchHits<StockInfoES> searchHits = elasticsearchOperations.search(stringQuery, StockInfoES.class);

        DomesticStockSearchESResponse.Builder responseBuilder = DomesticStockSearchESResponse.newBuilder();

        searchHits.forEach(searchHit -> {
            StockInfoES stockInfoES = searchHit.getContent();

            log.info("Get StockInfoES: {}", stockInfoES);
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
    public DomesticStockDailyByTsCodeAndDateRangeResponse getStockDailyByTsCodeAndDateRange(DomesticStockDailyByTsCodeAndDateRangeRequest request) {
        String tsCode = request.getTsCode();
        long startDate = request.getStartDate();
        long endDate = request.getEndDate();

        // 根据时间范围和股票代码查询股票日线行情
        List<StockDaily> stockDailyList = stockQuoteDao.getStockDailyByTsCodeAndDateRange(tsCode, startDate, endDate);

        // 构建响应对象
        DomesticStockDailyByTsCodeAndDateRangeResponse.Builder responseBuilder = DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder();

        // 将查询结果转换为日线行情对象
        for (StockDaily stockDaily : stockDailyList) {
            log.info("stockDaily: {}", stockDaily);
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
        int offset = request.getOffset();
        int limit = request.getLimit();

        // 根据交易日查询股票日线行情
        List<StockDaily> stockDailyList = stockQuoteDao.getStockDailyByTradeDate(tradeDate);

        // 构建响应对象
        DomesticStockDailyByTradeDateResponse.Builder responseBuilder = DomesticStockDailyByTradeDateResponse.newBuilder();

        // 将查询结果转换为日线行情对象
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

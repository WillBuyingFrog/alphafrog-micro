package world.willfrog.alphafrogmicro.domestic.fetch;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockInfoDao;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.DomesticStockStoreUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.TuShareRequestUtils;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStock.*;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticStockFetchServiceTriple.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@DubboService
@Slf4j
public class DomesticStockFetchServiceImpl extends DomesticStockFetchServiceImplBase {

    private final TuShareRequestUtils tuShareRequestUtils;
    private final DomesticStockStoreUtils domesticStockStoreUtils;
    private final StockInfoDao stockInfoDao;

    public DomesticStockFetchServiceImpl(TuShareRequestUtils tuShareRequestUtils,
                                         DomesticStockStoreUtils domesticStockStoreUtils,
                                         StockInfoDao stockInfoDao) {
        this.tuShareRequestUtils = tuShareRequestUtils;
        this.domesticStockStoreUtils = domesticStockStoreUtils;
        this.stockInfoDao = stockInfoDao;
    }

    @Override
    public DomesticStockDailyFetchByTradeDateResponse fetchStockDailyByTradeDate(
            DomesticStockDailyFetchByTradeDateRequest request
    ) {
        long tradeDateTimestamp = request.getTradeDate();
        int offset = request.getOffset();
        int limit = request.getLimit();

        String tradeDate = DateConvertUtils.convertTimestampToString(tradeDateTimestamp, "yyyyMMdd");

//        if (log.isDebugEnabled()) {
//            log.debug("stock_daily request trade_date={} offset={} limit={}", tradeDate, offset, limit);
//        }

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "daily");
        queryParams.put("trade_date", tradeDate);
        queryParams.put("limit", limit);
        queryParams.put("offset", offset);
        params.put("fields", "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg," +
                "vol,amount");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticStockDailyFetchByTradeDateResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");
//        if (log.isDebugEnabled()) {
//            log.debug("stock_daily response items={} fields={}", data == null ? 0 : data.size(),
//                    fields == null ? 0 : fields.size());
//        }

        int result = domesticStockStoreUtils.storeStockDailyByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticStockDailyFetchByTradeDateResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        } else {
//            if (log.isDebugEnabled()) {
//                log.debug("stock_daily stored_rows={}", result);
//            }
            return DomesticStockDailyFetchByTradeDateResponse.newBuilder().setStatus("success")
                    .setFetchedItemsCount(result).build();
        }
    }

    @Override
    public DomesticStockInfoFetchByMarketResponse fetchStockInfoByMarket(
            DomesticStockInfoFetchByMarketRequest request
    ) {
        String market = request.getMarket();
        int offset = request.getOffset();
        int limit = request.getLimit();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "stock_basic");
        if (market != null && !market.isBlank()) {
            queryParams.put("exchange", market);
        }
        queryParams.put("limit", limit);
        queryParams.put("offset", offset);
        params.put("fields", "ts_code,symbol,name,area,industry,fullname,enname,cnspell,market,exchange,curr_type," +
                "list_status,list_date,delist_date,is_hs,act_name, act_ent_type");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticStockInfoFetchByMarketResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticStockStoreUtils.storeStockInfoByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticStockInfoFetchByMarketResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        } else {
            return DomesticStockInfoFetchByMarketResponse.newBuilder().setStatus("success")
                    .setFetchedItemsCount(result).build();
        }
    }

    public int fetchStockDailyByDateRange(long startDateTimestamp, long endDateTimestamp, int offset, int limit) {
        int affectedRows = 0;

        String startDate = DateConvertUtils.convertTimestampToString(startDateTimestamp, "yyyyMMdd");
        String endDate = DateConvertUtils.convertTimestampToString(endDateTimestamp, "yyyyMMdd");

        List<String> stockTsCodeList = stockInfoDao.getStockTsCode(offset, limit);

        try {
            for (String stockTsCode : stockTsCodeList) {
                Map<String, Object> params = new HashMap<>();
                Map<String, Object> queryParams = new HashMap<>();

                params.put("api_name", "daily");
                queryParams.put("ts_code", stockTsCode);
                queryParams.put("start_date", startDate);
                queryParams.put("end_date", endDate);
                params.put("fields", "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg," +
                        "vol,amount");
                params.put("params", queryParams);

                JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

                if (response == null) {
                    continue;
                }

                JSONArray data = response.getJSONObject("data").getJSONArray("items");
                JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

                int result = domesticStockStoreUtils.storeStockDailyByRawTuShareOutput(data, fields);

                if (result < 0) {
                    log.error("Failed to store stock daily data for ts_code: {}", stockTsCode);
                    return -2;
                } else {
                    affectedRows += result;
                }

                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    log.error("Thread sleep interrupted", e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch stock daily data by date range", e);
            return -1;
        }

        return affectedRows;
    }
}

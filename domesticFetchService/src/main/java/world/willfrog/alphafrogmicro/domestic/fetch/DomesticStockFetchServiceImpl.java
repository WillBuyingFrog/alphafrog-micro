package world.willfrog.alphafrogmicro.domestic.fetch;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockInfoDao;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.DomesticStockStoreUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.TuShareRequestUtils;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStock.*;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticStockFetchServiceTriple.*;

import java.util.HashMap;
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

        int result = domesticStockStoreUtils.storeStockDailyByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticStockDailyFetchByTradeDateResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        } else {
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
        queryParams.put("exchange", market);
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
}

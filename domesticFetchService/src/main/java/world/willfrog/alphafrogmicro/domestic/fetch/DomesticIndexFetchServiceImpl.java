package world.willfrog.alphafrogmicro.domestic.fetch;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexInfoDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexInfo;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.DomesticIndexStoreUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.TuShareRequestUtils;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndex.*;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticIndexFetchServiceTriple.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@DubboService
@Slf4j
public class DomesticIndexFetchServiceImpl extends DomesticIndexFetchServiceImplBase {

    private final TuShareRequestUtils tuShareRequestUtils;
    private final DomesticIndexStoreUtils domesticIndexStoreUtils;
    private final IndexInfoDao indexInfoDao;

    public DomesticIndexFetchServiceImpl(TuShareRequestUtils tuShareRequestUtils,
                                         DomesticIndexStoreUtils domesticIndexStoreUtils,
                                         IndexInfoDao indexInfoDao) {
        this.tuShareRequestUtils = tuShareRequestUtils;
        this.domesticIndexStoreUtils = domesticIndexStoreUtils;
        this.indexInfoDao = indexInfoDao;
    }


    @Override
    public DomesticIndexInfoFetchByMarketResponse fetchDomesticIndexInfoByMarket(
            DomesticIndexInfoFetchByMarketRequest request) {

        String market = request.getMarket();
        int limit = request.getLimit();
        int offset = request.getOffset();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "index_basic");
        queryParams.put("market", market);
        queryParams.put("limit", limit);
        queryParams.put("offset", offset);
        params.put("fields", "ts_code,name,fullname,market,publisher,index_type," +
                "category,base_date,base_point,list_date,weight_rule,desc,exp_date");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticIndexInfoFetchByMarketResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticIndexStoreUtils.storeIndexInfoByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticIndexInfoFetchByMarketResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        } else {
            return DomesticIndexInfoFetchByMarketResponse.newBuilder().setStatus("success")
                    .setFetchedItemsCount(result).build();
        }
    }

    @Override
    public DomesticIndexDailyFetchByDateRangeResponse fetchDomesticIndexDailyByDateRange(
            DomesticIndexDailyFetchByDateRangeRequest request) {

        String tsCode = request.getTsCode();

        long startDateTimestamp = request.getStartDate();
        long endDateTimestamp = request.getEndDate();
        int limit = request.getLimit();
        int offset = request.getOffset();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "index_daily");
        queryParams.put("ts_code", tsCode);
        queryParams.put("start_date", DateConvertUtils.convertTimestampToString(startDateTimestamp, "yyyyMMdd"));
        queryParams.put("end_date", DateConvertUtils.convertTimestampToString(endDateTimestamp, "yyyyMMdd"));
        queryParams.put("limit", limit);
        queryParams.put("offset", offset);
        params.put("fields", "ts_code,trade_date,close,open,high,low,pre_close,change,pct_chg,vol,amount");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticIndexDailyFetchByDateRangeResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticIndexStoreUtils.storeIndexDailyByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticIndexDailyFetchByDateRangeResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        } else {
            return DomesticIndexDailyFetchByDateRangeResponse.newBuilder().setStatus("success")
                    .setFetchedItemsCount(result).build();
        }

    }

    @Override
    public DomesticIndexDailyFetchByTradeDateResponse fetchDomesticIndexDailyByTradeDate(
            DomesticIndexDailyFetchByTradeDateRequest request
    ) {

        // 从本地数据源中获得所有要爬取的指数
        List<String> allTsCode = indexInfoDao.getAllIndexInfoTsCodes(request.getOffset(), request.getLimit());

        if (allTsCode.isEmpty()) {
            log.error("No index info found in the database.");
            return DomesticIndexDailyFetchByTradeDateResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        long tradeDateTimestamp = request.getTradeDate();

        int _counter = 0;

        for (String tsCode : allTsCode) {

            // 对每个指数代码，爬取并储存指定日期的行情数据
            Map<String, Object> params = new HashMap<>();
            Map<String, Object> queryParams = new HashMap<>();

            params.put("api_name", "index_daily");
            queryParams.put("ts_code", tsCode);
            queryParams.put("trade_date", DateConvertUtils.convertTimestampToString(tradeDateTimestamp, "yyyyMMdd"));
            params.put("fields", "ts_code,trade_date,close,open,high,low,pre_close,change,pct_chg,vol,amount");
            params.put("params", queryParams);

            // 爬取
            JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

            if (response == null) {
                return DomesticIndexDailyFetchByTradeDateResponse.newBuilder().setStatus("failure")
                        .setFetchedItemsCount(-1).build();
            }

            JSONArray data = response.getJSONObject("data").getJSONArray("items");
            JSONArray fields = response.getJSONObject("data").getJSONArray("fields");


            // 储存
            int _result = domesticIndexStoreUtils.storeIndexDailyByRawTuShareOutput(data, fields);

            if (_result < 0) {
                log.error("Failed to store index daily data for ts_code {} on trade date {}", tsCode, tradeDateTimestamp);
                return DomesticIndexDailyFetchByTradeDateResponse.newBuilder().setStatus("failure")
                        .setFetchedItemsCount(_result).build();
            }

            _counter += _result;


            try{
                Thread.sleep(200);
            } catch (InterruptedException e) {
                log.error("Thread sleep interrupted.");
            }
        }

        return DomesticIndexDailyFetchByTradeDateResponse.newBuilder().setStatus("success")
                .setFetchedItemsCount(_counter).build();

    }

    @Override
    public DomesticIndexDailyFetchAllByDateRangeResponse fetchDomesticIndexDailyAllByDateRange(
            DomesticindexDailyFetchAllByDateRangeRequest request) {

        long startDateTimestamp = request.getStartDate();
        long endDateTimestamp = request.getEndDate();
        int limit = request.getLimit();
        int offset = request.getOffset();

        List<String> allTsCode = indexInfoDao.getAllIndexInfoTsCodes(offset, limit);

        if (allTsCode.isEmpty()) {
            log.error("No index info found in the database.");
            return DomesticIndexDailyFetchAllByDateRangeResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        int _counter = 0;

        for (String tsCode : allTsCode) {

            Map<String, Object> params = new HashMap<>();
            Map<String, Object> queryParams = new HashMap<>();

            params.put("api_name", "index_daily");
            queryParams.put("ts_code", tsCode);
            queryParams.put("start_date", DateConvertUtils.convertTimestampToString(startDateTimestamp, "yyyyMMdd"));
            queryParams.put("end_date", DateConvertUtils.convertTimestampToString(endDateTimestamp, "yyyyMMdd"));
            params.put("fields", "ts_code,trade_date,close,open,high,low,pre_close,change,pct_chg,vol,amount");
            params.put("params", queryParams);

            JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

            if (response == null) {
                return DomesticIndexDailyFetchAllByDateRangeResponse.newBuilder().setStatus("failure")
                        .setFetchedItemsCount(-1).build();
            }

            JSONArray data = response.getJSONObject("data").getJSONArray("items");
            JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

            int _result = domesticIndexStoreUtils.storeIndexDailyByRawTuShareOutput(data, fields);

            if (_result < 0) {
                log.error("Failed to store index daily data for ts_code {} between trade date {} and {}",
                        tsCode, startDateTimestamp, endDateTimestamp);
                return DomesticIndexDailyFetchAllByDateRangeResponse.newBuilder().setStatus("failure")
                        .setFetchedItemsCount(_result).build();
            }

            _counter += _result;

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                log.error("Thread sleep interrupted.");
            }
        }

        return DomesticIndexDailyFetchAllByDateRangeResponse.newBuilder()
                .setStatus("success").setFetchedItemsCount(_counter).build();
    }

    @Override
    public DomesticIndexWeightFetchByDateRangeResponse fetchDomesticIndexWeightByDateRange(
            DomesticIndexWeightFetchByDateRangeRequest request) {

        long startDateTimestamp = request.getStartDate();
        long endDateTimestamp = request.getEndDate();
        int limit = request.getLimit();
        int offset = request.getOffset();

        int _counter = 0;

        String startDate = DateConvertUtils.convertTimestampToString(startDateTimestamp, "yyyyMMdd");
        String endDate = DateConvertUtils.convertTimestampToString(endDateTimestamp, "yyyyMMdd");

        List<String> allTsCode = indexInfoDao.getAllIndexInfoTsCodes(offset, limit);

        for (String tsCode : allTsCode) {
            Map<String, Object> params = new HashMap<>();
            Map<String, Object> queryParams = new HashMap<>();

            params.put("api_name", "index_weight");
            queryParams.put("index_code", tsCode);
            queryParams.put("start_date", startDate);
            queryParams.put("end_date", endDate);
            params.put("fields", "index_code,con_code,trade_date,weight");
            params.put("params", queryParams);

            JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

            if (response == null) {
                return DomesticIndexWeightFetchByDateRangeResponse.newBuilder().setStatus("failure")
                        .setFetchedItemsCount(-1).build();
            }

            JSONArray data = response.getJSONObject("data").getJSONArray("items");
            JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

            int _result = domesticIndexStoreUtils.storeIndexWeightByRawTuShareOutput(data, fields);

            if (_result < 0) {
                log.error("Failed to store index weight data for ts_code {} between trade date {} and {}",
                        tsCode, startDate, endDate);
                return DomesticIndexWeightFetchByDateRangeResponse.newBuilder().setStatus("failure")
                        .setFetchedItemsCount(_result).build();
            }

            _counter += _result;

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                log.error("Thread sleep interrupted.");
            }
        }

        return DomesticIndexWeightFetchByDateRangeResponse.newBuilder().setStatus("success")
                .setFetchedItemsCount(_counter).build();
    }


}

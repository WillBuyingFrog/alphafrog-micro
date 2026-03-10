package world.willfrog.alphafrogmicro.domestic.fetch;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexInfoDao;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.DomesticIndexStoreUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.TuShareRequestUtils;
import world.willfrog.alphafrogmicro.domestic.idl.*;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticIndexFetchServiceTriple.*;

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
        if (market != null && !market.isBlank()) {
            queryParams.put("market", market);
        }
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

        log.debug("Sending Tushare request for Index Daily: {}", params);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            log.error("Tushare request returned null response. Params: {}", params);
            return DomesticIndexDailyFetchByDateRangeResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        if (log.isDebugEnabled()) {
            log.debug("Received Tushare response: {}", response.toJSONString());
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticIndexStoreUtils.storeIndexDailyByRawTuShareOutput(data, fields);

        if (result < 0) {
            log.error("Store index daily data failed! Result code: {}, TS Code: {}", result, tsCode);
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


    // ==================== 新增：大盘指数每日估值指标 ====================

    @Override
    public DomesticIndexDailyBasicFetchByTsCodeResponse fetchIndexDailyBasicByTsCode(
            DomesticIndexDailyBasicFetchByTsCodeRequest request) {

        String tsCode = request.getTsCode();
        String startDate = request.getStartDate();
        String endDate = request.getEndDate();
        int offset = request.getOffset();
        int limit = request.getLimit();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "index_dailybasic");
        queryParams.put("ts_code", tsCode);
        if (startDate != null && !startDate.isBlank()) {
            queryParams.put("start_date", startDate);
        }
        if (endDate != null && !endDate.isBlank()) {
            queryParams.put("end_date", endDate);
        }
        queryParams.put("limit", limit > 0 ? limit : 3000);
        queryParams.put("offset", offset);
        params.put("fields", "ts_code,trade_date,total_mv,float_mv,total_share,float_share," +
                "free_share,turnover_rate,turnover_rate_f,pe,pe_ttm,pb");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticIndexDailyBasicFetchByTsCodeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticIndexStoreUtils.storeIndexDailyBasicByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticIndexDailyBasicFetchByTsCodeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(result).build();
        }
        return DomesticIndexDailyBasicFetchByTsCodeResponse.newBuilder()
                .setStatus("success").setFetchedItemsCount(result).build();
    }

    @Override
    public DomesticIndexDailyBasicFetchByTradeDateResponse fetchIndexDailyBasicByTradeDate(
            DomesticIndexDailyBasicFetchByTradeDateRequest request) {

        String tradeDate = request.getTradeDate();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "index_dailybasic");
        queryParams.put("trade_date", tradeDate);
        params.put("fields", "ts_code,trade_date,total_mv,float_mv,total_share,float_share," +
                "free_share,turnover_rate,turnover_rate_f,pe,pe_ttm,pb");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticIndexDailyBasicFetchByTradeDateResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticIndexStoreUtils.storeIndexDailyBasicByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticIndexDailyBasicFetchByTradeDateResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(result).build();
        }
        return DomesticIndexDailyBasicFetchByTradeDateResponse.newBuilder()
                .setStatus("success").setFetchedItemsCount(result).build();
    }


    // ==================== 新增：申万行业分类 ====================

    @Override
    public DomesticSwIndustryClassifyFetchResponse fetchSwIndustryClassify(
            DomesticSwIndustryClassifyFetchRequest request) {

        String level = request.getLevel();
        String src = request.getSrc();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "index_classify");
        if (level != null && !level.isBlank()) {
            queryParams.put("level", level);
        }
        if (src != null && !src.isBlank()) {
            queryParams.put("src", src);
        }
        params.put("fields", "index_code,industry_name,parent_code,level,industry_code,is_pub,src");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticSwIndustryClassifyFetchResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticIndexStoreUtils.storeSwIndustryClassifyByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticSwIndustryClassifyFetchResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(result).build();
        }
        return DomesticSwIndustryClassifyFetchResponse.newBuilder()
                .setStatus("success").setFetchedItemsCount(result).build();
    }


    // ==================== 新增：申万行业成分 ====================

    @Override
    public DomesticSwIndustryMemberFetchByL1CodeResponse fetchSwIndustryMemberByL1Code(
            DomesticSwIndustryMemberFetchByL1CodeRequest request) {

        String l1Code = request.getL1Code();
        String isNew = request.getIsNew();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "index_member_all");
        queryParams.put("l1_code", l1Code);
        if (isNew != null && !isNew.isBlank()) {
            queryParams.put("is_new", isNew);
        }
        params.put("fields", "l1_code,l1_name,l2_code,l2_name,l3_code,l3_name,ts_code,name,in_date,out_date,is_new");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticSwIndustryMemberFetchByL1CodeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticIndexStoreUtils.storeSwIndustryMemberByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticSwIndustryMemberFetchByL1CodeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(result).build();
        }
        return DomesticSwIndustryMemberFetchByL1CodeResponse.newBuilder()
                .setStatus("success").setFetchedItemsCount(result).build();
    }


    // ==================== 新增：申万行业指数日线行情 ====================

    @Override
    public DomesticSwIndustryDailyFetchByTradeDateResponse fetchSwIndustryDailyByTradeDate(
            DomesticSwIndustryDailyFetchByTradeDateRequest request) {

        String tradeDate = request.getTradeDate();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "sw_daily");
        queryParams.put("trade_date", tradeDate);
        params.put("fields", "ts_code,trade_date,name,open,low,high,close,change,pct_change,vol,amount,pe,pb,float_mv,total_mv");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticSwIndustryDailyFetchByTradeDateResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticIndexStoreUtils.storeSwIndustryDailyByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticSwIndustryDailyFetchByTradeDateResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(result).build();
        }
        return DomesticSwIndustryDailyFetchByTradeDateResponse.newBuilder()
                .setStatus("success").setFetchedItemsCount(result).build();
    }

    @Override
    public DomesticSwIndustryDailyFetchByTsCodeResponse fetchSwIndustryDailyByTsCode(
            DomesticSwIndustryDailyFetchByTsCodeRequest request) {

        String tsCode = request.getTsCode();
        String startDate = request.getStartDate();
        String endDate = request.getEndDate();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "sw_daily");
        queryParams.put("ts_code", tsCode);
        if (startDate != null && !startDate.isBlank()) {
            queryParams.put("start_date", startDate);
        }
        if (endDate != null && !endDate.isBlank()) {
            queryParams.put("end_date", endDate);
        }
        params.put("fields", "ts_code,trade_date,name,open,low,high,close,change,pct_change,vol,amount,pe,pb,float_mv,total_mv");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticSwIndustryDailyFetchByTsCodeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticIndexStoreUtils.storeSwIndustryDailyByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticSwIndustryDailyFetchByTsCodeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(result).build();
        }
        return DomesticSwIndustryDailyFetchByTsCodeResponse.newBuilder()
                .setStatus("success").setFetchedItemsCount(result).build();
    }


    // ==================== 新增：中信行业成分 ====================

    @Override
    public DomesticCiIndexMemberFetchResponse fetchCiIndexMember(
            DomesticCiIndexMemberFetchRequest request) {

        String tsCode = request.getTsCode();
        String isNew = request.getIsNew();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "ci_index_member");
        if (tsCode != null && !tsCode.isBlank()) {
            queryParams.put("ts_code", tsCode);
        }
        if (isNew != null && !isNew.isBlank()) {
            queryParams.put("is_new", isNew);
        }
        params.put("fields", "l1_code,l1_name,l2_code,l2_name,l3_code,l3_name,ts_code,name,in_date,out_date,is_new");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticCiIndexMemberFetchResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticIndexStoreUtils.storeCiIndexMemberByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticCiIndexMemberFetchResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(result).build();
        }
        return DomesticCiIndexMemberFetchResponse.newBuilder()
                .setStatus("success").setFetchedItemsCount(result).build();
    }


}

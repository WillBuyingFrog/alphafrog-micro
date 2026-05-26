package world.willfrog.alphafrogmicro.domestic.fetch;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.DomesticStockStoreUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.TuShareRequestUtils;
import world.willfrog.alphafrogmicro.domestic.idl.*;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticStockFetchServiceTriple.*;

import java.util.HashMap;
import java.util.Map;

@Service
@DubboService
@Slf4j
public class DomesticStockFetchServiceImpl extends DomesticStockFetchServiceImplBase {

    private final TuShareRequestUtils tuShareRequestUtils;
    private final DomesticStockStoreUtils domesticStockStoreUtils;

    public DomesticStockFetchServiceImpl(TuShareRequestUtils tuShareRequestUtils,
                                         DomesticStockStoreUtils domesticStockStoreUtils) {
        this.tuShareRequestUtils = tuShareRequestUtils;
        this.domesticStockStoreUtils = domesticStockStoreUtils;
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
        String startDate = DateConvertUtils.convertTimestampToString(startDateTimestamp, "yyyyMMdd");
        String endDate = DateConvertUtils.convertTimestampToString(endDateTimestamp, "yyyyMMdd");

        // TuShare daily：可不指定 ts_code，仅 start_date、end_date 与 offset、limit 分页
        try {
            Map<String, Object> params = new HashMap<>();
            Map<String, Object> queryParams = new HashMap<>();

            params.put("api_name", "daily");
            queryParams.put("start_date", startDate);
            queryParams.put("end_date", endDate);
            queryParams.put("offset", offset);
            queryParams.put("limit", limit);
            params.put("fields", "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg," +
                    "vol,amount");
            params.put("params", queryParams);

            JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

            if (response == null) {
                return -1;
            }

            JSONArray data = response.getJSONObject("data").getJSONArray("items");
            JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

            int result = domesticStockStoreUtils.storeStockDailyByRawTuShareOutput(data, fields);

            if (result < 0) {
                log.error("Failed to store stock daily data for date range {}..{}", startDate, endDate);
                return -2;
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to fetch stock daily data by date range", e);
            return -1;
        }
    }

    // ==================== PR #35 新增：股票财务数据爬取方法 ====================

    // 4.2 利润表爬取
    @Override
    public DomesticStockIncomeFetchByPeriodResponse fetchStockIncomeByPeriod(
            DomesticStockIncomeFetchByPeriodRequest request) {
        
        String period = request.getPeriod();
        int offset = request.getOffset();
        int limit = request.getLimit();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "income_vip");
        queryParams.put("period", period);
        queryParams.put("report_type", "1");  // 合并报表
        queryParams.put("limit", limit > 0 ? limit : 3000);
        queryParams.put("offset", offset);
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticStockIncomeFetchByPeriodResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticStockStoreUtils.storeStockIncomeByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticStockIncomeFetchByPeriodResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(result).build();
        }
        return DomesticStockIncomeFetchByPeriodResponse.newBuilder().setStatus("success")
                .setFetchedItemsCount(result).build();
    }

    // 4.3 资产负债表爬取
    @Override
    public DomesticStockBalancesheetFetchByPeriodResponse fetchStockBalancesheetByPeriod(
            DomesticStockBalancesheetFetchByPeriodRequest request) {
        
        String period = request.getPeriod();
        int offset = request.getOffset();
        int limit = request.getLimit();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "balancesheet_vip");
        queryParams.put("period", period);
        queryParams.put("report_type", "1");  // 合并报表
        queryParams.put("limit", limit > 0 ? limit : 3000);
        queryParams.put("offset", offset);
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticStockBalancesheetFetchByPeriodResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticStockStoreUtils.storeStockBalancesheetByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticStockBalancesheetFetchByPeriodResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(result).build();
        }
        return DomesticStockBalancesheetFetchByPeriodResponse.newBuilder().setStatus("success")
                .setFetchedItemsCount(result).build();
    }

    // 4.4 现金流量表爬取
    @Override
    public DomesticStockCashflowFetchByPeriodResponse fetchStockCashflowByPeriod(
            DomesticStockCashflowFetchByPeriodRequest request) {
        
        String period = request.getPeriod();
        int offset = request.getOffset();
        int limit = request.getLimit();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "cashflow_vip");
        queryParams.put("period", period);
        queryParams.put("report_type", "1");  // 合并报表
        queryParams.put("limit", limit > 0 ? limit : 3000);
        queryParams.put("offset", offset);
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticStockCashflowFetchByPeriodResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticStockStoreUtils.storeStockCashflowByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticStockCashflowFetchByPeriodResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(result).build();
        }
        return DomesticStockCashflowFetchByPeriodResponse.newBuilder().setStatus("success")
                .setFetchedItemsCount(result).build();
    }

    // 4.5 业绩预告爬取
    @Override
    public DomesticStockForecastFetchByDateRangeResponse fetchStockForecastByDateRange(
            DomesticStockForecastFetchByDateRangeRequest request) {
        
        String startDate = request.getStartDate();
        String endDate = request.getEndDate();
        int offset = request.getOffset();
        int limit = request.getLimit();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "forecast_vip");
        queryParams.put("start_date", startDate);
        queryParams.put("end_date", endDate);
        queryParams.put("limit", limit > 0 ? limit : 3000);
        queryParams.put("offset", offset);
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticStockForecastFetchByDateRangeResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticStockStoreUtils.storeStockForecastByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticStockForecastFetchByDateRangeResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(result).build();
        }
        return DomesticStockForecastFetchByDateRangeResponse.newBuilder().setStatus("success")
                .setFetchedItemsCount(result).build();
    }

    // 4.6 业绩快报爬取
    @Override
    public DomesticStockExpressFetchByDateRangeResponse fetchStockExpressByDateRange(
            DomesticStockExpressFetchByDateRangeRequest request) {
        
        String startDate = request.getStartDate();
        String endDate = request.getEndDate();
        int offset = request.getOffset();
        int limit = request.getLimit();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "express_vip");
        queryParams.put("start_date", startDate);
        queryParams.put("end_date", endDate);
        queryParams.put("limit", limit > 0 ? limit : 3000);
        queryParams.put("offset", offset);
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticStockExpressFetchByDateRangeResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticStockStoreUtils.storeStockExpressByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticStockExpressFetchByDateRangeResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(result).build();
        }
        return DomesticStockExpressFetchByDateRangeResponse.newBuilder().setStatus("success")
                .setFetchedItemsCount(result).build();
    }

    // 4.7 卖方盈利预测爬取
    @Override
    public DomesticStockReportRcFetchByDateRangeResponse fetchStockReportRcByDateRange(
            DomesticStockReportRcFetchByDateRangeRequest request) {
        
        String startDate = request.getStartDate();
        String endDate = request.getEndDate();
        int offset = request.getOffset();
        int limit = request.getLimit();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "report_rc");
        queryParams.put("start_date", startDate);
        queryParams.put("end_date", endDate);
        queryParams.put("limit", limit > 0 ? limit : 3000);
        queryParams.put("offset", offset);
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticStockReportRcFetchByDateRangeResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticStockStoreUtils.storeStockReportRcByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticStockReportRcFetchByDateRangeResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(result).build();
        }
        return DomesticStockReportRcFetchByDateRangeResponse.newBuilder().setStatus("success")
                .setFetchedItemsCount(result).build();
    }

    // 4.8 个股资金流向爬取
    @Override
    public DomesticStockMoneyflowFetchByTradeDateResponse fetchStockMoneyflowByTradeDate(
            DomesticStockMoneyflowFetchByTradeDateRequest request) {
        
        String tradeDate = request.getTradeDate();
        String startDate = request.getStartDate();
        String endDate = request.getEndDate();
        int offset = request.getOffset();
        int limit = request.getLimit();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "moneyflow");
        
        // 优先使用 trade_date，如果不存在则使用 start_date/end_date 组合
        if (tradeDate != null && !tradeDate.isBlank()) {
            queryParams.put("trade_date", tradeDate);
        }
        if (startDate != null && !startDate.isBlank()) {
            queryParams.put("start_date", startDate);
        }
        if (endDate != null && !endDate.isBlank()) {
            queryParams.put("end_date", endDate);
        }
        if (offset > 0) {
            queryParams.put("offset", offset);
        }
        if (limit > 0) {
            queryParams.put("limit", limit);
        }
        
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticStockMoneyflowFetchByTradeDateResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticStockStoreUtils.storeStockMoneyflowByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticStockMoneyflowFetchByTradeDateResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(result).build();
        }
        return DomesticStockMoneyflowFetchByTradeDateResponse.newBuilder().setStatus("success")
                .setFetchedItemsCount(result).build();
    }

    // 4.9 前十大股东爬取
    @Override
    public DomesticStockTop10HoldersFetchByTsCodeResponse fetchStockTop10HoldersByTsCode(
            DomesticStockTop10HoldersFetchByTsCodeRequest request) {
        
        String tsCode = request.getTsCode();
        String startDate = request.getStartDate();
        String endDate = request.getEndDate();
        int offset = request.getOffset();
        int limit = request.getLimit();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "top10_holders");
        queryParams.put("ts_code", tsCode);
        if (startDate != null && !startDate.isBlank()) {
            queryParams.put("start_date", startDate);
        }
        if (endDate != null && !endDate.isBlank()) {
            queryParams.put("end_date", endDate);
        }
        queryParams.put("limit", limit > 0 ? limit : 100);
        queryParams.put("offset", offset);
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticStockTop10HoldersFetchByTsCodeResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticStockStoreUtils.storeStockTop10HoldersByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticStockTop10HoldersFetchByTsCodeResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(result).build();
        }
        return DomesticStockTop10HoldersFetchByTsCodeResponse.newBuilder().setStatus("success")
                .setFetchedItemsCount(result).build();
    }

    // 4.10 限售股解禁爬取
    @Override
    public DomesticStockShareFloatFetchByDateRangeResponse fetchStockShareFloatByDateRange(
            DomesticStockShareFloatFetchByDateRangeRequest request) {
        
        String startDate = request.getStartDate();
        String endDate = request.getEndDate();
        int offset = request.getOffset();
        int limit = request.getLimit();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "share_float");
        queryParams.put("start_date", startDate);
        queryParams.put("end_date", endDate);
        queryParams.put("limit", limit > 0 ? limit : 6000);
        queryParams.put("offset", offset);
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticStockShareFloatFetchByDateRangeResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticStockStoreUtils.storeStockShareFloatByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticStockShareFloatFetchByDateRangeResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(result).build();
        }
        return DomesticStockShareFloatFetchByDateRangeResponse.newBuilder().setStatus("success")
                .setFetchedItemsCount(result).build();
    }

    // ==================== 批量拉取全部股票日线（历史数据初始化） ====================
    @Override
    public DomesticStockDailyFetchAllByDateRangeResponse fetchStockDailyAllByDateRange(
            DomesticStockDailyFetchAllByDateRangeRequest request) {
        
        String startDate = request.getStartDate();
        String endDate = request.getEndDate();
        int offset = request.getOffset();
        int limit = request.getLimit();

        // 如果 limit 未提供或为 0，使用默认值
        int effectiveLimit = limit > 0 ? limit : 6000;
        if (limit <= 0) {
            log.warn("未提供 limit 参数，stock_daily (all by date range) 使用默认页大小 6000");
        }

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "daily");
        if (startDate != null && !startDate.isBlank()) {
            queryParams.put("start_date", startDate);
        }
        if (endDate != null && !endDate.isBlank()) {
            queryParams.put("end_date", endDate);
        }
        queryParams.put("offset", offset);
        queryParams.put("limit", effectiveLimit);
        params.put("fields", "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticStockDailyFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-1).build();
        }

        // 检查 TuShare 返回的错误码
        Integer code = response.getInteger("code");
        String msg = response.getString("msg");
        if (code != null && code != 0) {
            log.error("TuShare API 返回错误: code={}, msg={}, 请求参数: start_date={}, end_date={}, offset={}, limit={}", 
                    code, msg, startDate, endDate, offset, effectiveLimit);
            return DomesticStockDailyFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-4).build();
        }

        JSONObject responseData = response.getJSONObject("data");
        if (responseData == null) {
            log.error("TuShare API 返回的 data 为空，请求参数: start_date={}, end_date={}, offset={}, limit={}", 
                    startDate, endDate, offset, effectiveLimit);
            return DomesticStockDailyFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-2).build();
        }

        JSONArray data = responseData.getJSONArray("items");
        JSONArray fields = responseData.getJSONArray("fields");
        
        if (data == null || fields == null) {
            log.error("TuShare API 返回的 items 或 fields 为空，请求参数: start_date={}, end_date={}, offset={}, limit={}", 
                    startDate, endDate, offset, effectiveLimit);
            return DomesticStockDailyFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-3).build();
        }

        int result = domesticStockStoreUtils.storeStockDailyByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticStockDailyFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(result).build();
        }
        return DomesticStockDailyFetchAllByDateRangeResponse.newBuilder()
                .setStatus("success").setFetchedItemsCount(result).build();
    }
}

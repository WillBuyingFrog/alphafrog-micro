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
import world.willfrog.alphafrogmicro.domestic.fetch.utils.TuShareResponseUtils;
import world.willfrog.alphafrogmicro.domestic.idl.*;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticIndexFetchServiceTriple.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 国内指数数据抓取 Dubbo 服务实现。
 * 负责通过 TuShare 接口获取指数基本信息、日线行情、成分股权重、估值指标、申万/中信行业数据等，
 * 并调用 DomesticIndexStoreUtils 持久化到数据库。
 */
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


    /**
     * 按市场分页抓取指数基本信息（api_name=index_basic）。
     */
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

    /**
     * 按日期范围抓取单个指数的日线行情（api_name=index_daily）。
     * 对应 FetchTopicConsumer 中旧的 subType=3 逻辑（按 ts_code + 日期范围）。
     */
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

    /**
     * 按单个交易日抓取所有指数日线行情（subType=1）。
     * 先从本地 index_info 分批取 tsCode，再逐个代码请求 TuShare，
     * 支持 apiOffsetStart/End/Step 的内部分页循环。
     */
    @Override
    public DomesticIndexDailyFetchByTradeDateResponse fetchDomesticIndexDailyByTradeDate(
            DomesticIndexDailyFetchByTradeDateRequest request
    ) {

        // 从本地数据源中获得所有要爬取的指数（优先使用 indexOffset/indexLimit，兼容旧字段）
        int indexOffset = request.getIndexOffset();
        int indexLimit = request.getIndexLimit();
        if (indexLimit <= 0) {
            indexOffset = request.getOffset();
            indexLimit = request.getLimit();
        }
        List<String> allTsCode = indexInfoDao.getAllIndexInfoTsCodes(indexOffset, indexLimit);

        if (allTsCode.isEmpty()) {
            log.error("No index info found in the database.");
            return DomesticIndexDailyFetchByTradeDateResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        long tradeDateTimestamp = request.getTradeDate();

        int _counter = 0;

        int apiStart = request.getApiOffsetStart();
        int apiEnd = request.getApiOffsetEnd();
        int apiStep = request.getApiOffsetStep();
        boolean hasApiRange = apiStep > 0 && apiEnd >= apiStart;

        for (String tsCode : allTsCode) {
            if (hasApiRange) {
                for (int apiOff = apiStart; apiOff <= apiEnd; apiOff += apiStep) {
                    int pageResult = fetchIndexDailyByTradeDateSinglePage(tsCode, tradeDateTimestamp, apiOff, apiStep);
                    if (pageResult < 0) {
                        log.error("Failed to fetch index_daily for ts_code {} on trade date {} with apiOffset={}", tsCode, tradeDateTimestamp, apiOff);
                        return DomesticIndexDailyFetchByTradeDateResponse.newBuilder().setStatus("failure")
                                .setFetchedItemsCount(pageResult).build();
                    }
                    _counter += pageResult;
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        log.error("Thread sleep interrupted.");
                    }
                }
            } else {
                // 向后兼容：使用旧的单页 offset/limit
                int pageResult = fetchIndexDailyByTradeDateSinglePage(tsCode, tradeDateTimestamp, request.getOffset(), request.getLimit());
                if (pageResult < 0) {
                    log.error("Failed to fetch index_daily for ts_code {} on trade date {}", tsCode, tradeDateTimestamp);
                    return DomesticIndexDailyFetchByTradeDateResponse.newBuilder().setStatus("failure")
                            .setFetchedItemsCount(pageResult).build();
                }
                _counter += pageResult;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    log.error("Thread sleep interrupted.");
                }
            }
        }

        return DomesticIndexDailyFetchByTradeDateResponse.newBuilder().setStatus("success")
                .setFetchedItemsCount(_counter).build();

    }

    /**
     * 按日期范围批量抓取所有指数日线行情（subType=2/3）。
     * 同样采用"本地指数分批 + 逐个 tsCode 内循环分页"的双层机制。
     */
    @Override
    public DomesticIndexDailyFetchAllByDateRangeResponse fetchDomesticIndexDailyAllByDateRange(
            DomesticindexDailyFetchAllByDateRangeRequest request) {

        long startDateTimestamp = request.getStartDate();
        long endDateTimestamp = request.getEndDate();
        int indexOffset = request.getIndexOffset();
        int indexLimit = request.getIndexLimit();
        if (indexLimit <= 0) {
            indexOffset = request.getOffset();
            indexLimit = request.getLimit();
        }

        List<String> allTsCode = indexInfoDao.getAllIndexInfoTsCodes(indexOffset, indexLimit);

        if (allTsCode.isEmpty()) {
            log.error("No index info found in the database.");
            return DomesticIndexDailyFetchAllByDateRangeResponse.newBuilder().setStatus("failure")
                    .setFetchedItemsCount(-1).build();
        }

        int _counter = 0;

        int apiStart2 = request.getApiOffsetStart();
        int apiEnd2 = request.getApiOffsetEnd();
        int apiStep2 = request.getApiOffsetStep();
        boolean hasApiRange2 = apiStep2 > 0 && apiEnd2 >= apiStart2;

        for (String tsCode : allTsCode) {
            if (hasApiRange2) {
                for (int apiOff = apiStart2; apiOff <= apiEnd2; apiOff += apiStep2) {
                    int pageResult = fetchIndexDailyAllByDateRangeSinglePage(tsCode, startDateTimestamp, endDateTimestamp, apiOff, apiStep2);
                    if (pageResult < 0) {
                        log.error("Failed to fetch index_daily all for ts_code {} between {} and {} with apiOffset={}",
                                tsCode, startDateTimestamp, endDateTimestamp, apiOff);
                        return DomesticIndexDailyFetchAllByDateRangeResponse.newBuilder().setStatus("failure")
                                .setFetchedItemsCount(pageResult).build();
                    }
                    _counter += pageResult;
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        log.error("Thread sleep interrupted.");
                    }
                }
            } else {
                int pageResult = fetchIndexDailyAllByDateRangeSinglePage(tsCode, startDateTimestamp, endDateTimestamp, request.getOffset(), request.getLimit());
                if (pageResult < 0) {
                    log.error("Failed to fetch index_daily all for ts_code {} between {} and {}",
                            tsCode, startDateTimestamp, endDateTimestamp);
                    return DomesticIndexDailyFetchAllByDateRangeResponse.newBuilder().setStatus("failure")
                            .setFetchedItemsCount(pageResult).build();
                }
                _counter += pageResult;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    log.error("Thread sleep interrupted.");
                }
            }
        }

        return DomesticIndexDailyFetchAllByDateRangeResponse.newBuilder()
                .setStatus("success").setFetchedItemsCount(_counter).build();
    }

    /**
     * 按日期范围抓取指数成分股权重（api_name=index_weight）。
     * 逻辑与 index_daily 类似：本地指数分批 + 逐个 tsCode 内循环分页。
     */
    @Override
    public DomesticIndexWeightFetchByDateRangeResponse fetchDomesticIndexWeightByDateRange(
            DomesticIndexWeightFetchByDateRangeRequest request) {

        long startDateTimestamp = request.getStartDate();
        long endDateTimestamp = request.getEndDate();
        int indexOffset = request.getIndexOffset();
        int indexLimit = request.getIndexLimit();
        if (indexLimit <= 0) {
            indexOffset = request.getOffset();
            indexLimit = request.getLimit();
        }

        int _counter = 0;

        String startDate = DateConvertUtils.convertTimestampToString(startDateTimestamp, "yyyyMMdd");
        String endDate = DateConvertUtils.convertTimestampToString(endDateTimestamp, "yyyyMMdd");

        List<String> allTsCode = indexInfoDao.getAllIndexInfoTsCodes(indexOffset, indexLimit);

        int apiStart3 = request.getApiOffsetStart();
        int apiEnd3 = request.getApiOffsetEnd();
        int apiStep3 = request.getApiOffsetStep();
        boolean hasApiRange3 = apiStep3 > 0 && apiEnd3 >= apiStart3;

        for (String tsCode : allTsCode) {
            if (hasApiRange3) {
                for (int apiOff = apiStart3; apiOff <= apiEnd3; apiOff += apiStep3) {
                    int pageResult = fetchIndexWeightByDateRangeSinglePage(tsCode, startDate, endDate, apiOff, apiStep3);
                    if (pageResult < 0) {
                        log.error("Failed to fetch index_weight for ts_code {} between {} and {} with apiOffset={}",
                                tsCode, startDate, endDate, apiOff);
                        return DomesticIndexWeightFetchByDateRangeResponse.newBuilder().setStatus("failure")
                                .setFetchedItemsCount(pageResult).build();
                    }
                    _counter += pageResult;
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        log.error("Thread sleep interrupted.");
                    }
                }
            } else {
                int pageResult = fetchIndexWeightByDateRangeSinglePage(tsCode, startDate, endDate, request.getOffset(), request.getLimit());
                if (pageResult < 0) {
                    log.error("Failed to store index weight data for ts_code {} between trade date {} and {}",
                            tsCode, startDate, endDate);
                    return DomesticIndexWeightFetchByDateRangeResponse.newBuilder().setStatus("failure")
                            .setFetchedItemsCount(pageResult).build();
                }
                _counter += pageResult;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    log.error("Thread sleep interrupted.");
                }
            }
        }

        return DomesticIndexWeightFetchByDateRangeResponse.newBuilder().setStatus("success")
                .setFetchedItemsCount(_counter).build();
    }


    // ==================== 新增：大盘指数每日估值指标 ====================

    /**
     * 按指数代码和日期范围抓取大盘指数每日估值指标（api_name=index_dailybasic）。
     */
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

    /**
     * 按单个交易日抓取全部大盘指数估值指标（api_name=index_dailybasic）。
     */
    @Override
    public DomesticIndexDailyBasicFetchByTradeDateResponse fetchIndexDailyBasicByTradeDate(
            DomesticIndexDailyBasicFetchByTradeDateRequest request) {

        String tradeDate = request.getTradeDate();
        int offset = request.getOffset();
        int limit = request.getLimit();

        // 如果 limit 未提供或为 0，使用默认值并打印 warning
        int effectiveLimit = limit > 0 ? limit : 3000;
        if (limit <= 0) {
            log.warn("未提供 limit 参数，index_dailybasic 使用默认页大小 3000；不提供 limit 不是推荐做法");
        }

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "index_dailybasic");
        queryParams.put("trade_date", tradeDate);
        queryParams.put("offset", offset);
        queryParams.put("limit", effectiveLimit);
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

    /**
     * 按日期范围批量抓取全部大盘指数估值指标（历史数据初始化）。
     */
    @Override
    public DomesticIndexDailyBasicFetchAllByDateRangeResponse fetchIndexDailyBasicAllByDateRange(
            DomesticIndexDailyBasicFetchAllByDateRangeRequest request) {

        String startDate = request.getStartDate();
        String endDate = request.getEndDate();
        int offset = request.getOffset();
        int limit = request.getLimit();

        // 如果 limit 未提供或为 0，使用默认值
        int effectiveLimit = limit > 0 ? limit : 3000;
        if (limit <= 0) {
            log.warn("未提供 limit 参数，index_dailybasic (all by date range) 使用默认页大小 3000");
        }

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "index_dailybasic");
        if (startDate != null && !startDate.isBlank()) {
            queryParams.put("start_date", startDate);
        }
        if (endDate != null && !endDate.isBlank()) {
            queryParams.put("end_date", endDate);
        }
        queryParams.put("offset", offset);
        queryParams.put("limit", effectiveLimit);
        params.put("fields", "ts_code,trade_date,total_mv,float_mv,total_share,float_share," +
                "free_share,turnover_rate,turnover_rate_f,pe,pe_ttm,pb");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticIndexDailyBasicFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-1).build();
        }

        // 检查 TuShare 返回的错误码
        Integer code = response.getInteger("code");
        String msg = response.getString("msg");
        if (code != null && code != 0) {
            log.error("TuShare API 返回错误: code={}, msg={}, 请求参数: start_date={}, end_date={}, offset={}, limit={}", 
                    code, msg, startDate, endDate, offset, effectiveLimit);
            return DomesticIndexDailyBasicFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-4).build();
        }

        JSONObject responseData = response.getJSONObject("data");
        if (responseData == null) {
            log.error("TuShare API 返回的 data 为空，请求参数: start_date={}, end_date={}, offset={}, limit={}", 
                    startDate, endDate, offset, effectiveLimit);
            return DomesticIndexDailyBasicFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-2).build();
        }

        JSONArray data = responseData.getJSONArray("items");
        JSONArray fields = responseData.getJSONArray("fields");
        
        if (data == null || fields == null) {
            log.error("TuShare API 返回的 items 或 fields 为空，请求参数: start_date={}, end_date={}, offset={}, limit={}", 
                    startDate, endDate, offset, effectiveLimit);
            return DomesticIndexDailyBasicFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-3).build();
        }

        int result = domesticIndexStoreUtils.storeIndexDailyBasicByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticIndexDailyBasicFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(result).build();
        }
        return DomesticIndexDailyBasicFetchAllByDateRangeResponse.newBuilder()
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
        // src 默认使用 SW2021，显式设置
        if (src != null && !src.isBlank()) {
            queryParams.put("src", src);
        } else {
            queryParams.put("src", "SW2021");
            log.info("sw_industry_classify 使用默认 src=SW2021");
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
        String l2Code = request.getL2Code();
        String l3Code = request.getL3Code();
        String tsCode = request.getTsCode();
        String isNew = request.getIsNew();
        int offset = request.getOffset();
        int limit = request.getLimit();

        // 如果 limit 未提供或为 0，使用默认值并打印 warning
        int effectiveLimit = limit > 0 ? limit : 2000;
        if (limit <= 0) {
            log.warn("未提供 limit 参数，sw_industry_member 使用默认页大小 2000；不提供 limit 不是推荐做法");
        }

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "index_member_all");
        // 支持多种过滤条件组合
        if (l1Code != null && !l1Code.isBlank()) {
            queryParams.put("l1_code", l1Code);
        }
        if (l2Code != null && !l2Code.isBlank()) {
            queryParams.put("l2_code", l2Code);
        }
        if (l3Code != null && !l3Code.isBlank()) {
            queryParams.put("l3_code", l3Code);
        }
        if (tsCode != null && !tsCode.isBlank()) {
            queryParams.put("ts_code", tsCode);
        }
        if (isNew != null && !isNew.isBlank()) {
            queryParams.put("is_new", isNew);
        }
        queryParams.put("offset", offset);
        queryParams.put("limit", effectiveLimit);
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
        int offset = request.getOffset();
        int limit = request.getLimit();

        // 如果 limit 未提供或为 0，使用默认值并打印 warning
        int effectiveLimit = limit > 0 ? limit : 4000;
        if (limit <= 0) {
            log.warn("未提供 limit 参数，sw_industry_daily 使用默认页大小 4000；不提供 limit 不是推荐做法");
        }

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "sw_daily");
        queryParams.put("trade_date", tradeDate);
        queryParams.put("offset", offset);
        queryParams.put("limit", effectiveLimit);
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
        int offset = request.getOffset();
        int limit = request.getLimit();

        // 如果 limit 未提供或为 0，使用默认值并打印 warning
        int effectiveLimit = limit > 0 ? limit : 4000;
        if (limit <= 0) {
            log.warn("未提供 limit 参数，sw_industry_daily (by ts_code) 使用默认页大小 4000；不提供 limit 不是推荐做法");
        }

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
        queryParams.put("offset", offset);
        queryParams.put("limit", effectiveLimit);
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

    @Override
    public DomesticSwIndustryDailyFetchAllByDateRangeResponse fetchSwIndustryDailyAllByDateRange(
            DomesticSwIndustryDailyFetchAllByDateRangeRequest request) {

        String startDate = request.getStartDate();
        String endDate = request.getEndDate();
        int offset = request.getOffset();
        int limit = request.getLimit();

        // 如果 limit 未提供或为 0，使用默认值
        int effectiveLimit = limit > 0 ? limit : 4000;
        if (limit <= 0) {
            log.warn("未提供 limit 参数，sw_industry_daily (all by date range) 使用默认页大小 4000");
        }

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "sw_daily");
        if (startDate != null && !startDate.isBlank()) {
            queryParams.put("start_date", startDate);
        }
        if (endDate != null && !endDate.isBlank()) {
            queryParams.put("end_date", endDate);
        }
        queryParams.put("offset", offset);
        queryParams.put("limit", effectiveLimit);
        params.put("fields", "ts_code,trade_date,name,open,low,high,close,change,pct_change,vol,amount,pe,pb,float_mv,total_mv");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticSwIndustryDailyFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-1).build();
        }

        // 检查 TuShare 返回的错误码
        Integer code = response.getInteger("code");
        String msg = response.getString("msg");
        if (code != null && code != 0) {
            log.error("TuShare API 返回错误: code={}, msg={}, 请求参数: start_date={}, end_date={}, offset={}, limit={}", 
                    code, msg, startDate, endDate, offset, effectiveLimit);
            return DomesticSwIndustryDailyFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-4).build();
        }

        JSONObject responseData = response.getJSONObject("data");
        if (responseData == null) {
            log.error("TuShare API 返回的 data 为空，请求参数: start_date={}, end_date={}, offset={}, limit={}", 
                    startDate, endDate, offset, effectiveLimit);
            return DomesticSwIndustryDailyFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-2).build();
        }

        JSONArray data = responseData.getJSONArray("items");
        JSONArray fields = responseData.getJSONArray("fields");
        
        if (data == null || fields == null) {
            log.error("TuShare API 返回的 items 或 fields 为空，请求参数: start_date={}, end_date={}, offset={}, limit={}", 
                    startDate, endDate, offset, effectiveLimit);
            return DomesticSwIndustryDailyFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-3).build();
        }

        int result = domesticIndexStoreUtils.storeSwIndustryDailyByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticSwIndustryDailyFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(result).build();
        }
        return DomesticSwIndustryDailyFetchAllByDateRangeResponse.newBuilder()
                .setStatus("success").setFetchedItemsCount(result).build();
    }


    // ==================== 新增：中信行业成分 ====================

    @Override
    public DomesticCiIndexMemberFetchResponse fetchCiIndexMember(
            DomesticCiIndexMemberFetchRequest request) {

        String l1Code = request.getL1Code();
        String l2Code = request.getL2Code();
        String l3Code = request.getL3Code();
        String tsCode = request.getTsCode();
        String isNew = request.getIsNew();
        int offset = request.getOffset();
        int limit = request.getLimit();

        // 如果 limit 未提供或为 0，使用默认值并打印 warning
        int effectiveLimit = limit > 0 ? limit : 5000;
        if (limit <= 0) {
            log.warn("未提供 limit 参数，ci_index_member 使用默认页大小 5000；不提供 limit 不是推荐做法");
        }

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "ci_index_member");
        // 支持多种过滤条件组合
        if (l1Code != null && !l1Code.isBlank()) {
            queryParams.put("l1_code", l1Code);
        }
        if (l2Code != null && !l2Code.isBlank()) {
            queryParams.put("l2_code", l2Code);
        }
        if (l3Code != null && !l3Code.isBlank()) {
            queryParams.put("l3_code", l3Code);
        }
        if (tsCode != null && !tsCode.isBlank()) {
            queryParams.put("ts_code", tsCode);
        }
        if (isNew != null && !isNew.isBlank()) {
            queryParams.put("is_new", isNew);
        }
        queryParams.put("offset", offset);
        queryParams.put("limit", effectiveLimit);
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

    // ==================== 新增：中信行业指数日线行情（ci_daily）====================

    @Override
    public DomesticCiIndustryDailyFetchResponse fetchCiIndustryDaily(
            DomesticCiIndustryDailyFetchRequest request) {

        String tsCode = request.getTsCode();
        String tradeDate = request.getTradeDate();
        String startDate = request.getStartDate();
        String endDate = request.getEndDate();
        int offset = request.getOffset();
        int limit = request.getLimit();

        // 如果 limit 未提供或为 0，使用默认值并打印 warning
        int effectiveLimit = limit > 0 ? limit : 4000;
        if (limit <= 0) {
            log.warn("未提供 limit 参数，ci_industry_daily 使用默认页大小 4000；不提供 limit 不是推荐做法");
        }

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "ci_daily");
        if (tsCode != null && !tsCode.isBlank()) {
            queryParams.put("ts_code", tsCode);
        }
        if (tradeDate != null && !tradeDate.isBlank()) {
            queryParams.put("trade_date", tradeDate);
        }
        if (startDate != null && !startDate.isBlank()) {
            queryParams.put("start_date", startDate);
        }
        if (endDate != null && !endDate.isBlank()) {
            queryParams.put("end_date", endDate);
        }
        queryParams.put("offset", offset);
        queryParams.put("limit", effectiveLimit);
        params.put("fields", "ts_code,trade_date,open,low,high,close,pre_close,change,pct_change,vol,amount");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticCiIndustryDailyFetchResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticIndexStoreUtils.storeCiIndustryDailyByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticCiIndustryDailyFetchResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(result).build();
        }
        return DomesticCiIndustryDailyFetchResponse.newBuilder()
                .setStatus("success").setFetchedItemsCount(result).build();
    }

    @Override
    public DomesticCiIndustryDailyFetchAllByDateRangeResponse fetchCiIndustryDailyAllByDateRange(
            DomesticCiIndustryDailyFetchAllByDateRangeRequest request) {

        String startDate = request.getStartDate();
        String endDate = request.getEndDate();
        int offset = request.getOffset();
        int limit = request.getLimit();

        // 如果 limit 未提供或为 0，使用默认值
        int effectiveLimit = limit > 0 ? limit : 4000;
        if (limit <= 0) {
            log.warn("未提供 limit 参数，ci_industry_daily (all by date range) 使用默认页大小 4000");
        }

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "ci_daily");
        if (startDate != null && !startDate.isBlank()) {
            queryParams.put("start_date", startDate);
        }
        if (endDate != null && !endDate.isBlank()) {
            queryParams.put("end_date", endDate);
        }
        queryParams.put("offset", offset);
        queryParams.put("limit", effectiveLimit);
        params.put("fields", "ts_code,trade_date,open,low,high,close,pre_close,change,pct_change,vol,amount");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticCiIndustryDailyFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-1).build();
        }

        // 检查 TuShare 返回的错误码
        Integer code = response.getInteger("code");
        String msg = response.getString("msg");
        if (code != null && code != 0) {
            log.error("TuShare API 返回错误: code={}, msg={}, 请求参数: start_date={}, end_date={}, offset={}, limit={}", 
                    code, msg, startDate, endDate, offset, effectiveLimit);
            return DomesticCiIndustryDailyFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-4).build();
        }

        JSONObject responseData = response.getJSONObject("data");
        if (responseData == null) {
            log.error("TuShare API 返回的 data 为空，请求参数: start_date={}, end_date={}, offset={}, limit={}", 
                    startDate, endDate, offset, effectiveLimit);
            return DomesticCiIndustryDailyFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-2).build();
        }

        JSONArray data = responseData.getJSONArray("items");
        JSONArray fields = responseData.getJSONArray("fields");
        
        if (data == null || fields == null) {
            log.error("TuShare API 返回的 items 或 fields 为空，请求参数: start_date={}, end_date={}, offset={}, limit={}", 
                    startDate, endDate, offset, effectiveLimit);
            return DomesticCiIndustryDailyFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-3).build();
        }

        int result = domesticIndexStoreUtils.storeCiIndustryDailyByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticCiIndustryDailyFetchAllByDateRangeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(result).build();
        }
        return DomesticCiIndustryDailyFetchAllByDateRangeResponse.newBuilder()
                .setStatus("success").setFetchedItemsCount(result).build();
    }

    // ==================== 私有辅助方法：单层 TuShare 请求 ====================

    /**
     * 抓取单个 tsCode 在某一交易日的单页 index_daily 数据。
     *
     * @return 实际写入条数；请求失败返回 {@code -1}，数据为空返回 {@code 0}
     */
    private int fetchIndexDailyByTradeDateSinglePage(String tsCode, long tradeDateTimestamp, int apiOffset, int apiLimit) {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();
        params.put("api_name", "index_daily");
        queryParams.put("ts_code", tsCode);
        queryParams.put("trade_date", DateConvertUtils.convertTimestampToString(tradeDateTimestamp, "yyyyMMdd"));
        queryParams.put("offset", apiOffset);
        queryParams.put("limit", apiLimit > 0 ? apiLimit : 5000);
        params.put("fields", "ts_code,trade_date,close,open,high,low,pre_close,change,pct_chg,vol,amount");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);
        if (response == null) {
            log.warn("TuShare 返回 null，请求失败: ts_code={}, trade_date={}, apiOffset={}", tsCode, tradeDateTimestamp, apiOffset);
            return -1;
        }
        JSONObject dataObj = response.getJSONObject("data");
        if (dataObj == null) {
            log.warn("TuShare 响应缺少 'data' 字段: ts_code={}, trade_date={}, apiOffset={}", tsCode, tradeDateTimestamp, apiOffset);
            return -1;
        }
        JSONArray data = dataObj.getJSONArray("items");
        JSONArray fields = dataObj.getJSONArray("fields");
        if (data == null || data.isEmpty()) {
            return 0;
        }
        return domesticIndexStoreUtils.storeIndexDailyByRawTuShareOutput(data, fields);
    }

    /** 抓取单个 tsCode 在某一日期范围内的单页 index_daily 数据 */
    private int fetchIndexDailyAllByDateRangeSinglePage(String tsCode, long startDateTimestamp, long endDateTimestamp, int apiOffset, int apiLimit) {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();
        params.put("api_name", "index_daily");
        queryParams.put("ts_code", tsCode);
        queryParams.put("start_date", DateConvertUtils.convertTimestampToString(startDateTimestamp, "yyyyMMdd"));
        queryParams.put("end_date", DateConvertUtils.convertTimestampToString(endDateTimestamp, "yyyyMMdd"));
        queryParams.put("offset", apiOffset);
        queryParams.put("limit", apiLimit > 0 ? apiLimit : 5000);
        params.put("fields", "ts_code,trade_date,close,open,high,low,pre_close,change,pct_chg,vol,amount");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);
        TuShareResponseUtils.DataWrapper wrapper = TuShareResponseUtils.extractData(response, "index_daily");
        if (wrapper == null || !wrapper.hasData()) {
            return 0;
        }
        return domesticIndexStoreUtils.storeIndexDailyByRawTuShareOutput(wrapper.getItems(), wrapper.getFields());
    }

    /**
     * 抓取单个 tsCode 在某一日期范围内的单页 index_weight 数据。
     *
     * @return 实际写入条数；请求失败返回 {@code -1}，数据为空返回 {@code 0}
     */
    private int fetchIndexWeightByDateRangeSinglePage(String tsCode, String startDate, String endDate, int apiOffset, int apiLimit) {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();
        params.put("api_name", "index_weight");
        queryParams.put("index_code", tsCode != null ? tsCode : "");
        queryParams.put("trade_date", "");
        queryParams.put("start_date", startDate);
        queryParams.put("end_date", endDate);
        queryParams.put("offset", apiOffset);
        queryParams.put("limit", apiLimit > 0 ? apiLimit : 5000);
        params.put("fields", "index_code,con_code,trade_date,weight");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);
        if (response == null) {
            log.warn("TuShare 返回 null，请求失败: index_weight ts_code={}, startDate={}, endDate={}", tsCode, startDate, endDate);
            return -1;
        }
        JSONObject dataObj = response.getJSONObject("data");
        if (dataObj == null) {
            log.warn("TuShare 响应缺少 'data' 字段: index_weight ts_code={}, startDate={}, endDate={}", tsCode, startDate, endDate);
            return -1;
        }
        JSONArray data = dataObj.getJSONArray("items");
        JSONArray fields = dataObj.getJSONArray("fields");
        if (data == null || data.isEmpty()) {
            return 0;
        }
        return domesticIndexStoreUtils.storeIndexWeightByRawTuShareOutput(data, fields);
    }

    /**
     * 按日期范围直接抓取指数成分股权重（不经过本地指数分批，直接将 offset/limit 传给 TuShare）。
     * 对应 taskSubType = 4 的直接分页模式。
     */
    public DomesticIndexWeightFetchByDateRangeResponse fetchDomesticIndexWeightDirectByDateRange(
            DomesticIndexWeightFetchByDateRangeRequest request) {
        String startDate = DateConvertUtils.convertTimestampToString(request.getStartDate(), "yyyyMMdd");
        String endDate = DateConvertUtils.convertTimestampToString(request.getEndDate(), "yyyyMMdd");
        int offset = request.getOffset();
        int limit = request.getLimit();
        int result = fetchIndexWeightByDateRangeSinglePage(null, startDate, endDate, offset, limit);
        return DomesticIndexWeightFetchByDateRangeResponse.newBuilder()
                .setStatus(result >= 0 ? "success" : "failure")
                .setFetchedItemsCount(result)
                .build();
    }

}

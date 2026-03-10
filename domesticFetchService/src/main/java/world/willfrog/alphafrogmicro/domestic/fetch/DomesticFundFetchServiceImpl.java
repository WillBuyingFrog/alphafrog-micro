package world.willfrog.alphafrogmicro.domestic.fetch;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundInfoDao;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.DomesticFundStoreUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.TuShareRequestUtils;
import world.willfrog.alphafrogmicro.domestic.idl.*;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticFundFetchServiceTriple.DomesticFundFetchServiceImplBase;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@DubboService
@Service
@Slf4j
public class DomesticFundFetchServiceImpl extends DomesticFundFetchServiceImplBase {

    private final TuShareRequestUtils tuShareRequestUtils;
    private final DomesticFundStoreUtils domesticFundStoreUtils;

    private final FundInfoDao fundInfoDao;

    public DomesticFundFetchServiceImpl(TuShareRequestUtils tuShareRequestUtils,
                                        DomesticFundStoreUtils domesticFundStoreUtils,
                                        FundInfoDao fundInfoDao) {
        this.tuShareRequestUtils = tuShareRequestUtils;
        this.domesticFundStoreUtils = domesticFundStoreUtils;
        this.fundInfoDao = fundInfoDao;
    }



    @Override
    public DomesticFundInfoFetchByMarketResponse fetchDomesticFundInfoByMarket(DomesticFundInfoFetchByMarketRequest request) {

        String market = request.getMarket();
        int offset = request.getOffset();
        int limit = request.getLimit();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "fund_basic");
        if (market != null && !market.isBlank()) {
            queryParams.put("market", market);
        }
        queryParams.put("offset", offset);
        queryParams.put("limit", limit);
        params.put("params", queryParams);
        params.put("fields", "ts_code,name,management,custodian,fund_type,found_date,due_date,list_date,issue_date," +
                "delist_date,issue_amount,m_fee,c_fee,duration_year,p_value,min_amount,exp_return,benchmark,status," +
                "invest_type,type,trustee,purc_startdate,redm_startdate,market");

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);


        if(response == null) {
            return DomesticFundInfoFetchByMarketResponse.newBuilder()
                    .setStatus("failure")
                    .setFetchedItemsCount(0)
                    .build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");

        int affectedRows = domesticFundStoreUtils.storeFundInfosByRawTuShareOutput(data);

        if(affectedRows < 0){
            return DomesticFundInfoFetchByMarketResponse.newBuilder()
                    .setStatus("failure")
                    .setFetchedItemsCount(affectedRows)
                    .build();
        } else {
            return DomesticFundInfoFetchByMarketResponse.newBuilder()
                    .setStatus("success")
                    .setFetchedItemsCount(affectedRows)
                    .build();
        }
    }

    @Override
    public DomesticFundNavFetchByTradeDateResponse fetchDomesticFundNavByTradeDate(DomesticFundNavFetchByTradeDateRequest request) {

        long tradeDateTimestamp = request.getTradeDateTimestamp();
        String tradeDate = DateConvertUtils.convertTimestampToString(tradeDateTimestamp, "yyyyMMdd");
        int offset = request.getOffset();
        int limit = request.getLimit();

//        if (log.isDebugEnabled()) {
//            log.debug("fund_nav request trade_date={} offset={} limit={}", tradeDate, offset, limit);
//        }

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "fund_nav");
        queryParams.put("nav_date", tradeDate);
        queryParams.put("offset", offset);
        queryParams.put("limit", limit);

        params.put("params", queryParams);
        // 所有条目都要爬取
        params.put("fields", "ts_code,ann_date,nav_date,unit_nav,accum_nav,accum_div,net_asset,total_netasset,adj_nav");

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if(response == null) {
            return DomesticFundNavFetchByTradeDateResponse.newBuilder()
                    .setStatus("failure")
                    .setFetchedItemsCount(0)
                    .build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
//        if (log.isDebugEnabled()) {
//            log.debug("fund_nav response items={}", data == null ? 0 : data.size());
//        }

        int affectedRows = domesticFundStoreUtils.storeFundNavsByRawFullTuShareOutput(data);

        if(affectedRows < 0) {
            return DomesticFundNavFetchByTradeDateResponse.newBuilder()
                    .setStatus("failure")
                    .setFetchedItemsCount(affectedRows)
                    .build();
        } else {
//            if (log.isDebugEnabled()) {
//                log.debug("fund_nav stored_rows={}", affectedRows);
//            }
            return DomesticFundNavFetchByTradeDateResponse.newBuilder()
                    .setStatus("success")
                    .setFetchedItemsCount(affectedRows)
                    .build();
        }

    }

    @Override
    public DomesticFundPortfolioFetchByDateRangeResponse fetchDomesticFundPortfolioByDateRange(DomesticFundPortfolioFetchByDateRangeRequest request) {

        long startDateTimestamp = request.getStartDateTimestamp();
        long endDateTimestamp = request.getEndDateTimestamp();
        String startDate = DateConvertUtils.convertTimestampToString(startDateTimestamp, "yyyyMMdd");
        String endDate = DateConvertUtils.convertTimestampToString(endDateTimestamp, "yyyyMMdd");

        int offset = request.getOffset();
        int limit = request.getLimit();

        List<String> fundTsCodeList = fundInfoDao.getFundTsCode(offset, limit);

        int totalFetchedItems = 0;

        for (String fundTsCode : fundTsCodeList) {
            try {
                Map<String, Object> params = new HashMap<>();
                Map<String, Object> queryParams = new HashMap<>();

                params.put("api_name", "fund_portfolio");
                queryParams.put("ts_code", fundTsCode);
                queryParams.put("start_date", startDate);
                queryParams.put("end_date", endDate);
                params.put("params", queryParams);
                params.put("fields", "ts_code,ann_date,end_date,symbol,mkv,amount,stk_mkv_ratio,stk_float_ratio");

                JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

                if(response == null) {
                    return DomesticFundPortfolioFetchByDateRangeResponse.newBuilder()
                            .setStatus("failure")
                            .setFetchedItemsCount(totalFetchedItems)
                            .build();
                }

                JSONArray data = response.getJSONObject("data").getJSONArray("items");

                int affectedRows = domesticFundStoreUtils.storeFundPortfoliosByRawTuShareOutput(data);

                if(affectedRows < 0) {
                    return DomesticFundPortfolioFetchByDateRangeResponse.newBuilder()
                            .setStatus("failure")
                            .setFetchedItemsCount(totalFetchedItems)
                            .build();
                } else {
                    totalFetchedItems += affectedRows;
                }
            } catch (Exception e) {
                log.error("Error fetching portfolio for fund: " + fundTsCode, e);
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                log.error("Thread sleep interrupted");
            }
        }

        return DomesticFundPortfolioFetchByDateRangeResponse.newBuilder()
                .setStatus("success")
                .setFetchedItemsCount(totalFetchedItems)
                .build();

    }


    // ==================== 新增：基金管理人 ====================

    @Override
    public DomesticFundCompanyFetchResponse fetchFundCompany(
            DomesticFundCompanyFetchRequest request) {

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "fund_company");
        params.put("params", queryParams);
        params.put("fields", "name,shortname,short_enname,province,city,address,phone,office,website," +
                "chairman,manager,reg_capital,setup_date,end_date,employees,main_business,org_code,credit_code");

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticFundCompanyFetchResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticFundStoreUtils.storeFundCompanyByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticFundCompanyFetchResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(result).build();
        }
        return DomesticFundCompanyFetchResponse.newBuilder()
                .setStatus("success").setFetchedItemsCount(result).build();
    }


    // ==================== 新增：基金经理 ====================

    @Override
    public DomesticFundManagerFetchByTsCodeResponse fetchFundManagerByTsCode(
            DomesticFundManagerFetchByTsCodeRequest request) {

        String tsCode = request.getTsCode();
        int offset = request.getOffset();
        int limit = request.getLimit();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "fund_manager");
        queryParams.put("ts_code", tsCode);
        queryParams.put("offset", offset);
        queryParams.put("limit", limit > 0 ? limit : 5000);
        params.put("params", queryParams);
        params.put("fields", "ts_code,ann_date,name,gender,birth_year,edu,nationality,begin_date,end_date,resume");

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticFundManagerFetchByTsCodeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticFundStoreUtils.storeFundManagerByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticFundManagerFetchByTsCodeResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(result).build();
        }
        return DomesticFundManagerFetchByTsCodeResponse.newBuilder()
                .setStatus("success").setFetchedItemsCount(result).build();
    }


    // ==================== 新增：基金份额 ====================

    @Override
    public DomesticFundShareFetchByTradeDateResponse fetchFundShareByTradeDate(
            DomesticFundShareFetchByTradeDateRequest request) {

        String tradeDate = request.getTradeDate();
        int offset = request.getOffset();
        int limit = request.getLimit();

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "fund_share");
        queryParams.put("trade_date", tradeDate);
        queryParams.put("offset", offset);
        queryParams.put("limit", limit > 0 ? limit : 2000);
        params.put("params", queryParams);
        params.put("fields", "ts_code,trade_date,fd_share");

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticFundShareFetchByTradeDateResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticFundStoreUtils.storeFundShareByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticFundShareFetchByTradeDateResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(result).build();
        }
        return DomesticFundShareFetchByTradeDateResponse.newBuilder()
                .setStatus("success").setFetchedItemsCount(result).build();
    }


    // ==================== 新增：ETF 份额规模 ====================

    @Override
    public DomesticEtfShareSizeFetchByTradeDateResponse fetchEtfShareSizeByTradeDate(
            DomesticEtfShareSizeFetchByTradeDateRequest request) {

        String tradeDate = request.getTradeDate();
        String exchange = request.hasExchange() ? request.getExchange() : null;

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "etf_share_size");
        queryParams.put("trade_date", tradeDate);
        if (exchange != null && !exchange.isBlank()) {
            queryParams.put("exchange", exchange);
        }
        params.put("params", queryParams);
        params.put("fields", "trade_date,ts_code,etf_name,total_share,total_size,nav,close,exchange");

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticEtfShareSizeFetchByTradeDateResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(-1).build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticFundStoreUtils.storeEtfShareSizeByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticEtfShareSizeFetchByTradeDateResponse.newBuilder()
                    .setStatus("failure").setFetchedItemsCount(result).build();
        }
        return DomesticEtfShareSizeFetchByTradeDateResponse.newBuilder()
                .setStatus("success").setFetchedItemsCount(result).build();
    }


}

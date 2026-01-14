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
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFund.*;
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
        queryParams.put("market", market);
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

        int affectedRows = domesticFundStoreUtils.storeFundNavsByRawFullTuShareOutput(data);

        if(affectedRows < 0) {
            return DomesticFundNavFetchByTradeDateResponse.newBuilder()
                    .setStatus("failure")
                    .setFetchedItemsCount(affectedRows)
                    .build();
        } else {
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


}

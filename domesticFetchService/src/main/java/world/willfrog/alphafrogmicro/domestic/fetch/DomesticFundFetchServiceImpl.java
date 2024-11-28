package world.willfrog.alphafrogmicro.domestic.fetch;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;

import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.DomesticFundStoreUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.TuShareRequestUtils;
import world.willfrog.alphafrogmicro.service.domestic.fetch.DomesticFundFetchService;


import com.alibaba.fastjson.JSONArray;
import java.util.HashMap;
import java.util.Map;

@DubboService
@Service
@Slf4j
public class DomesticFundFetchServiceImpl implements DomesticFundFetchService {

    private final TuShareRequestUtils tuShareRequestUtils;

    private final DomesticFundStoreUtils domesticFundStoreUtils;

    public DomesticFundFetchServiceImpl(TuShareRequestUtils tuShareRequestUtils,
                                        DomesticFundStoreUtils domesticFundStoreUtils) {
        this.tuShareRequestUtils = tuShareRequestUtils;
        this.domesticFundStoreUtils = domesticFundStoreUtils;
    }

    @Override
    public int directFetchFundNavByNavDateAndMarket(String navDate, String market, int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        // 构建请求参数组合
        params.put("api_name", "fund_nav");
        queryParams.put("nav_date", navDate);
        if (market != null) {
            queryParams.put("market", market);
        }
        queryParams.put("offset", offset);
        queryParams.put("limit", limit);
        params.put("params", queryParams);
        // 所有条目都要爬取
        params.put("fields", "ts_code,ann_date,nav_date,unit_nav,accum_nav,accum_div,net_asset,total_netasset,adj_nav");

        boolean hasMore = true;
        int queriedRows = 0;
        while(hasMore){
            JSONObject res = tuShareRequestUtils.createTusharePostRequest(params);

            if (res == null) {
                return -2;
            }

            JSONArray fields = res.getJSONObject("data").getJSONArray("fields");
            JSONArray data = res.getJSONObject("data").getJSONArray("items");
            hasMore = res.getJSONObject("data").getBoolean("has_more");
            queriedRows += domesticFundStoreUtils.storeFundNavsByRawFullTuShareOutput(data);
            if(hasMore) {
                offset += limit;
                queryParams.put("offset", offset);
            }
        }

        // 插入所有爬取到的基金净值
        return queriedRows;
    }

    @Override
    public int batchFetchFundNavByTradeDate(long tradeDateTimestamp) {

        String tradeDateStr;
        try{
            tradeDateStr = DateConvertUtils.convertTimestampToString(tradeDateTimestamp, "yyyyMMdd");
//            String testDateStr1 = "20230106";
//            String testDateStr2 = "20230107";
//
//            log.info("Date {} converted to {}", testDateStr1, dateConvertUtils.convertDateStrToLong(testDateStr1, "yyyyMMdd"));
//            log.info("Date {} converted to {}", testDateStr2, dateConvertUtils.convertDateStrToLong(testDateStr2, "yyyyMMdd"));
        } catch (Exception e) {
            log.error("Failed to convert timestamp to string", e);
            return -1;
        }

        int result;
        log.info("Start to fetch fund nav data for trade date: {}", tradeDateStr);

        result = directFetchFundNavByNavDateAndMarket(tradeDateStr, null, 0, 15000);
        if(result < 0){
            log.error("Error fetching fund nav data");
            return result;
        }

        return result;
    }

}

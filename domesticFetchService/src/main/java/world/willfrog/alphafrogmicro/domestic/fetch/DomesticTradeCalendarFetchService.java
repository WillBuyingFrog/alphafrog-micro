package world.willfrog.alphafrogmicro.domestic.fetch;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.DomesticTradeCalendarStoreUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.TuShareRequestUtils;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndex;
import world.willfrog.alphafrogmicro.domestic.idl.DubboDomesticTradeCalendarFetchServiceTriple.*;

import java.util.HashMap;
import java.util.Map;

@Service
@DubboService
@Slf4j
public class DomesticTradeCalendarFetchService extends DomesticTradeCalendarFetchServiceImplBase {

    private final DomesticTradeCalendarStoreUtils domesticTradeCalendarStoreUtils;
    private final TuShareRequestUtils tuShareRequestUtils;

    public DomesticTradeCalendarFetchService(DomesticTradeCalendarStoreUtils domesticTradeCalendarStoreUtils,
                                             TuShareRequestUtils tuShareRequestUtils) {
        this.domesticTradeCalendarStoreUtils = domesticTradeCalendarStoreUtils;
        this.tuShareRequestUtils = tuShareRequestUtils;
    }

    @Override
    public DomesticIndex.DomesticTradeCalendarFetchByDateRangeResponse fetchDomesticTradeCalendarByDateRange(
            DomesticIndex.DomesticTradeCalendarFetchByDateRangeRequest request
    ) {
        long startDateTimestamp = request.getStartDate();
        long endDateTimestamp = request.getEndDate();
        int limit = request.getLimit();
        int offset = request.getOffset();

        String startDate = DateConvertUtils.convertTimestampToString(startDateTimestamp, "yyyyMMdd");
        String endDate = DateConvertUtils.convertTimestampToString(endDateTimestamp, "yyyyMMdd");

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> queryParams = new HashMap<>();

        params.put("api_name", "trade_cal");
        queryParams.put("exchange", "SSE");
        queryParams.put("start_date", startDate);
        queryParams.put("end_date", endDate);
        queryParams.put("limit", limit);
        queryParams.put("offset", offset);
        params.put("fields", "exchange,cal_date,is_open,pretrade_date");
        params.put("params", queryParams);

        JSONObject response = tuShareRequestUtils.createTusharePostRequest(params);

        if (response == null) {
            return DomesticIndex.DomesticTradeCalendarFetchByDateRangeResponse.newBuilder()
                    .setStatus("failure")
                    .setFetchedItemsCount(-1)
                    .build();
        }

        JSONArray data = response.getJSONObject("data").getJSONArray("items");
        JSONArray fields = response.getJSONObject("data").getJSONArray("fields");

        int result = domesticTradeCalendarStoreUtils.storeDomesticTradeCalendarByRawTuShareOutput(data, fields);

        if (result < 0) {
            return DomesticIndex.DomesticTradeCalendarFetchByDateRangeResponse.newBuilder()
                    .setStatus("failure")
                    .setFetchedItemsCount(-1)
                    .build();
        } else {
            return DomesticIndex.DomesticTradeCalendarFetchByDateRangeResponse.newBuilder()
                    .setStatus("success")
                    .setFetchedItemsCount(result)
                    .build();
        }
    }
}

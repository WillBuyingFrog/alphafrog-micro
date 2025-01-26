package world.willfrog.alphafrogmicro.domestic.fetch;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndex;

@Service
@Slf4j
public class FetchTopicConsumer {

    private final DomesticIndexFetchServiceImpl domesticIndexFetchService;

    public FetchTopicConsumer(DomesticIndexFetchServiceImpl domesticIndexFetchService){
        this.domesticIndexFetchService = domesticIndexFetchService;
    }


    @KafkaListener(topics = "fetch_topic", groupId = "alphafrog-micro")
    public void listenFetchTask(String message, Acknowledgment acknowledgment){
        log.info("Received fetch task: {}", message);
        JSONObject rawMessageJSON;
        try{
            rawMessageJSON = JSONObject.parseObject(message);
        } catch (Exception e) {
            log.error("Failed to parse message: {}", message);
            acknowledgment.acknowledge();
            return;
        }

        try{
            String taskName = rawMessageJSON.getString("task_name");
            int taskSubType = rawMessageJSON.getIntValue("task_sub_type");
            JSONObject taskParams = rawMessageJSON.getJSONObject("task_params");

            int result;

            switch (taskName) {
                case "index_info":
                    result = -1;
                    break;
                case "index_quote":
                    if (taskSubType == 1) {
                        long tradeDateTimestamp = taskParams.getLong("trade_date_timestamp");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticIndex.DomesticIndexDailyFetchByTradeDateRequest request =
                                DomesticIndex.DomesticIndexDailyFetchByTradeDateRequest.newBuilder()
                                        .setTradeDate(tradeDateTimestamp).setOffset(offset).setLimit(limit).build();
                        result = domesticIndexFetchService.fetchDomesticIndexDailyByTradeDate(request).getFetchedItemsCount();
                    } else if (taskSubType == 2){
                        long startDateTimestamp = taskParams.getLong("start_date_timestamp");
                        long endDateTimestamp = taskParams.getLong("end_date_timestamp");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticIndex.DomesticindexDailyFetchAllByDateRangeRequest request =
                                DomesticIndex.DomesticindexDailyFetchAllByDateRangeRequest.newBuilder()
                                        .setStartDate(startDateTimestamp).setEndDate(endDateTimestamp)
                                        .setOffset(offset).setLimit(limit).build();
                        result = domesticIndexFetchService.fetchDomesticIndexDailyAllByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "index_weight":
                    if (taskSubType == 1) {
                        long startDateTimestamp = taskParams.getLong("start_date_timestamp");
                        long endDateTimestamp = taskParams.getLong("end_date_timestamp");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticIndex.DomesticIndexWeightFetchByDateRangeRequest request =
                                DomesticIndex.DomesticIndexWeightFetchByDateRangeRequest.newBuilder()
                                        .setStartDate(startDateTimestamp).setEndDate(endDateTimestamp)
                                        .setOffset(offset).setLimit(limit)
                                        .build();
                        result = domesticIndexFetchService.fetchDomesticIndexWeightByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;
                case "fund_nav":
                    // 0: 爬取指定交易日范围内的所有基金净值
                    result = -1;
                    break;
                default:
                    result = -2;
                    break;
            }
            acknowledgment.acknowledge();
            log.info("Task result : {}", result);
        } catch (Exception e){
            log.error("Failed to start task: {}", message);
            log.error("Stack trace", e);
            acknowledgment.acknowledge();
        }
    }
}

package world.willfrog.alphafrogmicro.domestic.fetch;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.service.domestic.fetch.DomesticFundFetchService;

@Service
@Slf4j
public class FetchTopicConsumer {

    private final DomesticFundFetchService domesticFundFetchService;

    public FetchTopicConsumer(DomesticFundFetchService domesticFundFetchService){
        this.domesticFundFetchService = domesticFundFetchService;
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
                    result = -1;
                    break;
                case "fund_info":
                    result = -1;
                    break;
                case "fund_nav":
                    // 0: 爬取指定交易日范围内的所有基金净值
                    if (taskSubType == 0) {
                        long tradeDateTimestamp = taskParams.getLongValue("trade_date_timestamp");
                        result = domesticFundFetchService.batchFetchFundNavByTradeDate(tradeDateTimestamp);
                    } else {
                        result = -1;
                    }
                    break;
                default:
                    result = -2;
                    break;
            }
            acknowledgment.acknowledge();
            log.info("Task result : {}", result);
        } catch (Exception e){
            log.error("Failed to start task: {}", message);
            acknowledgment.acknowledge();
        }
    }
}

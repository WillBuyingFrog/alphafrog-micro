package world.willfrog.alphafrogmicro.domestic.fetch;

import com.alibaba.fastjson.JSONObject;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.domestic.fetch.config.DomesticFetchRabbitConfig;
import world.willfrog.alphafrogmicro.domestic.idl.*;

@Service
@Slf4j
public class FetchTopicConsumer {

    private final DomesticIndexFetchServiceImpl domesticIndexFetchService;
    private final DomesticFundFetchServiceImpl domesticFundFetchService;
    private final DomesticStockFetchServiceImpl domesticStockFetchService;
    private final DomesticTradeCalendarFetchService domesticTradeCalendarFetchService;
    private final RabbitTemplate rabbitTemplate;

    public FetchTopicConsumer(DomesticIndexFetchServiceImpl domesticIndexFetchService,
                              DomesticFundFetchServiceImpl domesticFundFetchService,
                              DomesticStockFetchServiceImpl domesticStockFetchService,
                              DomesticTradeCalendarFetchService domesticTradeCalendarFetchService,
                              RabbitTemplate rabbitTemplate) {
        this.domesticIndexFetchService = domesticIndexFetchService;
        this.domesticFundFetchService = domesticFundFetchService;
        this.domesticStockFetchService = domesticStockFetchService;
        this.domesticTradeCalendarFetchService = domesticTradeCalendarFetchService;
        this.rabbitTemplate = rabbitTemplate;
    }


    @RabbitListener(queues = DomesticFetchRabbitConfig.FETCH_TASK_QUEUE)
    public void listenFetchTask(String message,
                                Channel channel,
                                @Header(AmqpHeaders.DELIVERY_TAG) long tag){
        boolean success = false;
        log.info("Received fetch task [V2-DEBUG]: {}", message);

        String taskUuid = null;
        String taskName = null;
        Integer taskSubTypeValue = null;

        try{
            JSONObject rawMessageJSON = JSONObject.parseObject(message);
            if (rawMessageJSON == null) {
                throw new IllegalArgumentException("Invalid message JSON payload");
            }
            taskUuid = rawMessageJSON.getString("task_uuid");
            taskName = rawMessageJSON.getString("task_name");
            taskSubTypeValue = rawMessageJSON.getInteger("task_sub_type");
            int taskSubType = rawMessageJSON.getIntValue("task_sub_type");
            JSONObject taskParams = rawMessageJSON.getJSONObject("task_params");
            if (taskParams == null) {
                taskParams = new JSONObject();
            }

            int result;

            if (taskName == null) {
                result = -2;
                sendTaskResult(taskUuid, null, taskSubTypeValue, result, "Missing task_name");
                return;
            }

            switch (taskName) {
                case "index_info":
                    if (taskSubType == 1) {
                        String market = taskParams.getString("market");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticIndexInfoFetchByMarketRequest.Builder builder =
                                DomesticIndexInfoFetchByMarketRequest.newBuilder()
                                        .setOffset(offset).setLimit(limit);
                        if (market != null && !market.isBlank()) {
                            builder.setMarket(market);
                        }
                        DomesticIndexInfoFetchByMarketRequest request = builder.build();
                        result = domesticIndexFetchService.fetchDomesticIndexInfoByMarket(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;
                case "index_quote":
                    if (taskSubType == 1) {
                        long tradeDateTimestamp = taskParams.getLong("trade_date_timestamp");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticIndexDailyFetchByTradeDateRequest request =
                                DomesticIndexDailyFetchByTradeDateRequest.newBuilder()
                                        .setTradeDate(tradeDateTimestamp).setOffset(offset).setLimit(limit).build();
                        result = domesticIndexFetchService.fetchDomesticIndexDailyByTradeDate(request).getFetchedItemsCount();
                    } else if (taskSubType == 2){
                        long startDateTimestamp = taskParams.getLong("start_date_timestamp");
                        long endDateTimestamp = taskParams.getLong("end_date_timestamp");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticindexDailyFetchAllByDateRangeRequest request =
                                DomesticindexDailyFetchAllByDateRangeRequest.newBuilder()
                                        .setStartDate(startDateTimestamp).setEndDate(endDateTimestamp)
                                        .setOffset(offset).setLimit(limit).build();
                        result = domesticIndexFetchService.fetchDomesticIndexDailyAllByDateRange(request).getFetchedItemsCount();
                    } else if (taskSubType == 3) {
                        String tsCode = taskParams.getString("ts_code");
                        long startDateTimestamp = taskParams.getLong("start_date_timestamp");
                        long endDateTimestamp = taskParams.getLong("end_date_timestamp");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticIndexDailyFetchByDateRangeRequest request =
                                DomesticIndexDailyFetchByDateRangeRequest.newBuilder()
                                        .setTsCode(tsCode).setStartDate(startDateTimestamp).setEndDate(endDateTimestamp)
                                        .setOffset(offset).setLimit(limit).build();
                        result = domesticIndexFetchService.fetchDomesticIndexDailyByDateRange(request).getFetchedItemsCount();
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
                        DomesticIndexWeightFetchByDateRangeRequest request =
                                DomesticIndexWeightFetchByDateRangeRequest.newBuilder()
                                        .setStartDate(startDateTimestamp).setEndDate(endDateTimestamp)
                                        .setOffset(offset).setLimit(limit)
                                        .build();
                        result = domesticIndexFetchService.fetchDomesticIndexWeightByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;
                case "fund_info":
                    if (taskSubType == 1) {
                        String market = taskParams.getString("market");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticFundInfoFetchByMarketRequest.Builder builder =
                                DomesticFundInfoFetchByMarketRequest.newBuilder()
                                        .setOffset(offset).setLimit(limit);
                        if (market != null && !market.isBlank()) {
                            builder.setMarket(market);
                        }
                        DomesticFundInfoFetchByMarketRequest request = builder.build();
                        result = domesticFundFetchService.fetchDomesticFundInfoByMarket(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;
                case "fund_nav":
                    // 0: 爬取指定交易日范围内的所有基金净值
                    if (taskSubType == 1) {
                        long tradeDateTimestamp = taskParams.getLong("trade_date_timestamp");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticFundNavFetchByTradeDateRequest request =
                                DomesticFundNavFetchByTradeDateRequest.newBuilder()
                                        .setTradeDateTimestamp(tradeDateTimestamp)
                                        .setOffset(offset).setLimit(limit)
                                        .build();
                        result = domesticFundFetchService.fetchDomesticFundNavByTradeDate(request).getFetchedItemsCount();
                        Thread.sleep(200);
                    } else {
                        result = -1;
                    }
                    break;
                case "fund_portfolio":
                    if (taskSubType == 1){
                        long startDateTimestamp = taskParams.getLong("start_date_timestamp");
                        long endDateTimestamp = taskParams.getLong("end_date_timestamp");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticFundPortfolioFetchByDateRangeRequest request =
                                DomesticFundPortfolioFetchByDateRangeRequest.newBuilder()
                                        .setStartDateTimestamp(startDateTimestamp).setEndDateTimestamp(endDateTimestamp)
                                        .setOffset(offset).setLimit(limit)
                                        .build();
                        result = domesticFundFetchService.fetchDomesticFundPortfolioByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_info":
                    if (taskSubType == 1) {
                        String market = taskParams.getString("market");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticStockInfoFetchByMarketRequest.Builder builder =
                                DomesticStockInfoFetchByMarketRequest.newBuilder()
                                        .setOffset(offset).setLimit(limit);
                        if (market != null && !market.isBlank()) {
                            builder.setMarket(market);
                        }
                        DomesticStockInfoFetchByMarketRequest request = builder.build();
                        result = domesticStockFetchService.fetchStockInfoByMarket(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;
                case "stock_daily":
                    if (taskSubType == 1) {
                        long tradeDateTimestamp = taskParams.getLong("trade_date_timestamp");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticStockDailyFetchByTradeDateRequest request =
                                DomesticStockDailyFetchByTradeDateRequest.newBuilder()
                                        .setTradeDate(tradeDateTimestamp).setOffset(offset).setLimit(limit).build();
                        result = domesticStockFetchService.fetchStockDailyByTradeDate(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;
                case "stock_quote":
                    if (taskSubType == 1) {
                        long startDateTimestamp = taskParams.getLong("start_date_timestamp");
                        long endDateTimestamp = taskParams.getLong("end_date_timestamp");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");

                        result = domesticStockFetchService.fetchStockDailyByDateRange(startDateTimestamp, endDateTimestamp, offset, limit);
                    } else {
                        result = -1;
                    }
                    break;
                case "trade_calendar":
                    if (taskSubType == 1) {
                        long startDateTimestamp = taskParams.getLong("start_date_timestamp");
                        long endDateTimestamp = taskParams.getLong("end_date_timestamp");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticTradeCalendarFetchByDateRangeRequest request =
                                DomesticTradeCalendarFetchByDateRangeRequest.newBuilder()
                                        .setStartDate(startDateTimestamp).setEndDate(endDateTimestamp)
                                        .setOffset(offset).setLimit(limit)
                                        .build();
                        result = domesticTradeCalendarFetchService.fetchDomesticTradeCalendarByDateRange(request)
                                .getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;
                default:
                    result = -2;
                    break;
            }
            log.info("Task result : {}", result);
            sendTaskResult(taskUuid, taskName, taskSubTypeValue, result, null);
            success = true;
        } catch (Exception e){
            log.error("Failed to start task: {}", message);
            log.error("Stack trace", e);
            if (taskUuid != null && !taskUuid.isBlank()) {
                sendTaskResult(taskUuid, taskName, taskSubTypeValue, -1, e.getMessage());
            }
        } finally {
            try {
                if (success) {
                    channel.basicAck(tag, false);
                } else {
                    channel.basicNack(tag, false, false);
                }
            } catch (Exception ackException) {
                log.error("Failed to ack/nack fetch task message", ackException);
            }
        }
    }

    private void sendTaskResult(String taskUuid,
                                String taskName,
                                Integer taskSubType,
                                int fetchedItemsCount,
                                String message) {
        if (taskUuid == null || taskUuid.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("Skip sending task result because task_uuid is blank");
            }
            return;
        }
        JSONObject payload = new JSONObject();
        payload.put("task_uuid", taskUuid);
        payload.put("task_name", taskName);
        payload.put("task_sub_type", taskSubType);
        payload.put("fetched_items_count", fetchedItemsCount);
        payload.put("status", fetchedItemsCount >= 0 ? "success" : "failure");
        if (message != null && !message.isBlank()) {
            payload.put("message", message);
        }
        payload.put("updated_at", System.currentTimeMillis());
        try {
            if (log.isDebugEnabled()) {
                log.debug("Sending fetch task result exchange={} routingKey={} payload={}",
                        DomesticFetchRabbitConfig.FETCH_RESULT_EXCHANGE,
                        DomesticFetchRabbitConfig.FETCH_RESULT_ROUTING_KEY,
                        payload.toJSONString());
            }
            rabbitTemplate.convertAndSend(
                    DomesticFetchRabbitConfig.FETCH_RESULT_EXCHANGE,
                    DomesticFetchRabbitConfig.FETCH_RESULT_ROUTING_KEY,
                    payload.toJSONString());
        } catch (Exception e) {
            log.error("Failed to send fetch task result for {}", taskUuid, e);
        }
    }
}

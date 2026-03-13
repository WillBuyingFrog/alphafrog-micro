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
                        // 按交易日期爬取全部股票日线
                        long tradeDateTimestamp = taskParams.getLong("trade_date_timestamp");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticStockDailyFetchByTradeDateRequest request =
                                DomesticStockDailyFetchByTradeDateRequest.newBuilder()
                                        .setTradeDate(tradeDateTimestamp).setOffset(offset).setLimit(limit).build();
                        result = domesticStockFetchService.fetchStockDailyByTradeDate(request).getFetchedItemsCount();
                    } else if (taskSubType == 3) {
                        // 按日期范围批量爬取全部（历史数据初始化）
                        String startDate = taskParams.getString("start_date");
                        String endDate = taskParams.getString("end_date");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticStockDailyFetchAllByDateRangeRequest request =
                                DomesticStockDailyFetchAllByDateRangeRequest.newBuilder()
                                        .setStartDate(startDate).setEndDate(endDate)
                                        .setOffset(offset).setLimit(limit)
                                        .build();
                        result = domesticStockFetchService.fetchStockDailyAllByDateRange(request).getFetchedItemsCount();
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

                // ==================== 新增指数接口 ====================

                case "index_daily_basic":
                    if (taskSubType == 1) {
                        // 按指数代码+日期范围爬取
                        String tsCode = taskParams.getString("ts_code");
                        String startDate = taskParams.getString("start_date");
                        String endDate = taskParams.getString("end_date");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticIndexDailyBasicFetchByTsCodeRequest request =
                                DomesticIndexDailyBasicFetchByTsCodeRequest.newBuilder()
                                        .setTsCode(tsCode).setStartDate(startDate).setEndDate(endDate)
                                        .setOffset(offset).setLimit(limit)
                                        .build();
                        result = domesticIndexFetchService.fetchIndexDailyBasicByTsCode(request).getFetchedItemsCount();
                    } else if (taskSubType == 2) {
                        // 按交易日期爬取当日全部，支持分页
                        String tradeDate = taskParams.getString("trade_date");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticIndexDailyBasicFetchByTradeDateRequest request =
                                DomesticIndexDailyBasicFetchByTradeDateRequest.newBuilder()
                                        .setTradeDate(tradeDate)
                                        .setOffset(offset)
                                        .setLimit(limit)
                                        .build();
                        result = domesticIndexFetchService.fetchIndexDailyBasicByTradeDate(request).getFetchedItemsCount();
                    } else if (taskSubType == 3) {
                        // 按日期范围批量爬取全部（历史数据初始化）
                        String startDate = taskParams.getString("start_date");
                        String endDate = taskParams.getString("end_date");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticIndexDailyBasicFetchAllByDateRangeRequest request =
                                DomesticIndexDailyBasicFetchAllByDateRangeRequest.newBuilder()
                                        .setStartDate(startDate).setEndDate(endDate)
                                        .setOffset(offset).setLimit(limit)
                                        .build();
                        result = domesticIndexFetchService.fetchIndexDailyBasicAllByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "sw_industry_classify":
                    if (taskSubType == 1) {
                        // 支持空 task_params 直接拉全量，默认使用 SW2021
                        String level = taskParams.getString("level");
                        String src = taskParams.getString("src");
                        DomesticSwIndustryClassifyFetchRequest.Builder builder =
                                DomesticSwIndustryClassifyFetchRequest.newBuilder();
                        if (level != null && !level.isBlank()) {
                            builder.setLevel(level);
                        }
                        // src 默认使用 SW2021，但只在传入时设置，否则让 service 层处理默认值
                        if (src != null && !src.isBlank()) {
                            builder.setSrc(src);
                        }
                        DomesticSwIndustryClassifyFetchRequest request = builder.build();
                        result = domesticIndexFetchService.fetchSwIndustryClassify(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "sw_industry_member":
                    if (taskSubType == 1) {
                        // 支持多种过滤条件组合，包括仅 offset/limit 的全量分页模式
                        String l1Code = taskParams.getString("l1_code");
                        String l2Code = taskParams.getString("l2_code");
                        String l3Code = taskParams.getString("l3_code");
                        String tsCode = taskParams.getString("ts_code");
                        String isNew = taskParams.getString("is_new");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticSwIndustryMemberFetchByL1CodeRequest.Builder builder =
                                DomesticSwIndustryMemberFetchByL1CodeRequest.newBuilder();
                        if (l1Code != null && !l1Code.isBlank()) {
                            builder.setL1Code(l1Code);
                        }
                        if (l2Code != null && !l2Code.isBlank()) {
                            builder.setL2Code(l2Code);
                        }
                        if (l3Code != null && !l3Code.isBlank()) {
                            builder.setL3Code(l3Code);
                        }
                        if (tsCode != null && !tsCode.isBlank()) {
                            builder.setTsCode(tsCode);
                        }
                        builder.setIsNew(isNew != null && !isNew.isBlank() ? isNew : "Y");
                        builder.setOffset(offset);
                        builder.setLimit(limit);
                        DomesticSwIndustryMemberFetchByL1CodeRequest request = builder.build();
                        result = domesticIndexFetchService.fetchSwIndustryMemberByL1Code(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "sw_industry_daily":
                    if (taskSubType == 1) {
                        // 按交易日期爬取当日全部行业指数，支持分页
                        String tradeDate = taskParams.getString("trade_date");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticSwIndustryDailyFetchByTradeDateRequest request =
                                DomesticSwIndustryDailyFetchByTradeDateRequest.newBuilder()
                                        .setTradeDate(tradeDate)
                                        .setOffset(offset)
                                        .setLimit(limit)
                                        .build();
                        result = domesticIndexFetchService.fetchSwIndustryDailyByTradeDate(request).getFetchedItemsCount();
                    } else if (taskSubType == 2) {
                        // 按指数代码+日期范围爬取，支持分页
                        String tsCode = taskParams.getString("ts_code");
                        String startDate = taskParams.getString("start_date");
                        String endDate = taskParams.getString("end_date");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticSwIndustryDailyFetchByTsCodeRequest request =
                                DomesticSwIndustryDailyFetchByTsCodeRequest.newBuilder()
                                        .setTsCode(tsCode).setStartDate(startDate).setEndDate(endDate)
                                        .setOffset(offset).setLimit(limit)
                                        .build();
                        result = domesticIndexFetchService.fetchSwIndustryDailyByTsCode(request).getFetchedItemsCount();
                    } else if (taskSubType == 3) {
                        // 按日期范围批量爬取全部（历史数据初始化）
                        String startDate = taskParams.getString("start_date");
                        String endDate = taskParams.getString("end_date");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticSwIndustryDailyFetchAllByDateRangeRequest request =
                                DomesticSwIndustryDailyFetchAllByDateRangeRequest.newBuilder()
                                        .setStartDate(startDate).setEndDate(endDate)
                                        .setOffset(offset).setLimit(limit)
                                        .build();
                        result = domesticIndexFetchService.fetchSwIndustryDailyAllByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "ci_index_member":
                    if (taskSubType == 1) {
                        // 中信行业成分（不是中证指数），支持多种过滤条件
                        String l1Code = taskParams.getString("l1_code");
                        String l2Code = taskParams.getString("l2_code");
                        String l3Code = taskParams.getString("l3_code");
                        String tsCode = taskParams.getString("ts_code");
                        String isNew = taskParams.getString("is_new");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticCiIndexMemberFetchRequest.Builder builder =
                                DomesticCiIndexMemberFetchRequest.newBuilder();
                        if (l1Code != null && !l1Code.isBlank()) {
                            builder.setL1Code(l1Code);
                        }
                        if (l2Code != null && !l2Code.isBlank()) {
                            builder.setL2Code(l2Code);
                        }
                        if (l3Code != null && !l3Code.isBlank()) {
                            builder.setL3Code(l3Code);
                        }
                        if (tsCode != null && !tsCode.isBlank()) {
                            builder.setTsCode(tsCode);
                        }
                        builder.setIsNew(isNew != null && !isNew.isBlank() ? isNew : "Y");
                        builder.setOffset(offset);
                        builder.setLimit(limit);
                        DomesticCiIndexMemberFetchRequest request = builder.build();
                        result = domesticIndexFetchService.fetchCiIndexMember(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "ci_industry_daily":
                    if (taskSubType == 1) {
                        // 中信行业指数日线行情（ci_daily），按交易日期爬取
                        String tradeDate = taskParams.getString("trade_date");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticCiIndustryDailyFetchRequest request =
                                DomesticCiIndustryDailyFetchRequest.newBuilder()
                                        .setTradeDate(tradeDate != null ? tradeDate : "")
                                        .setOffset(offset)
                                        .setLimit(limit)
                                        .build();
                        result = domesticIndexFetchService.fetchCiIndustryDaily(request).getFetchedItemsCount();
                    } else if (taskSubType == 2) {
                        // 按指数代码+日期范围爬取
                        String tsCode = taskParams.getString("ts_code");
                        String startDate = taskParams.getString("start_date");
                        String endDate = taskParams.getString("end_date");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticCiIndustryDailyFetchRequest request =
                                DomesticCiIndustryDailyFetchRequest.newBuilder()
                                        .setTsCode(tsCode != null ? tsCode : "")
                                        .setStartDate(startDate != null ? startDate : "")
                                        .setEndDate(endDate != null ? endDate : "")
                                        .setOffset(offset)
                                        .setLimit(limit)
                                        .build();
                        result = domesticIndexFetchService.fetchCiIndustryDaily(request).getFetchedItemsCount();
                    } else if (taskSubType == 3) {
                        // 按日期范围批量爬取全部（历史数据初始化）
                        String startDate = taskParams.getString("start_date");
                        String endDate = taskParams.getString("end_date");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticCiIndustryDailyFetchAllByDateRangeRequest request =
                                DomesticCiIndustryDailyFetchAllByDateRangeRequest.newBuilder()
                                        .setStartDate(startDate != null ? startDate : "")
                                        .setEndDate(endDate != null ? endDate : "")
                                        .setOffset(offset)
                                        .setLimit(limit)
                                        .build();
                        result = domesticIndexFetchService.fetchCiIndustryDailyAllByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                // ==================== 新增基金接口 ====================

                case "fund_company":
                    if (taskSubType == 1) {
                        DomesticFundCompanyFetchRequest request =
                                DomesticFundCompanyFetchRequest.newBuilder().build();
                        result = domesticFundFetchService.fetchFundCompany(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "fund_manager":
                    if (taskSubType == 1) {
                        // 基金经理，支持多种过滤条件
                        String tsCode = taskParams.getString("ts_code");
                        String annDate = taskParams.getString("ann_date");
                        String name = taskParams.getString("name");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticFundManagerFetchByTsCodeRequest.Builder builder =
                                DomesticFundManagerFetchByTsCodeRequest.newBuilder();
                        if (tsCode != null && !tsCode.isBlank()) {
                            builder.setTsCode(tsCode);
                        }
                        if (annDate != null && !annDate.isBlank()) {
                            builder.setAnnDate(annDate);
                        }
                        if (name != null && !name.isBlank()) {
                            builder.setName(name);
                        }
                        builder.setOffset(offset);
                        builder.setLimit(limit);
                        DomesticFundManagerFetchByTsCodeRequest request = builder.build();
                        result = domesticFundFetchService.fetchFundManagerByTsCode(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "fund_share":
                    if (taskSubType == 1) {
                        // 基金份额，支持多种参数组合
                        String tsCode = taskParams.getString("ts_code");
                        String tradeDate = taskParams.getString("trade_date");
                        String startDate = taskParams.getString("start_date");
                        String endDate = taskParams.getString("end_date");
                        String market = taskParams.getString("market");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticFundShareFetchByTradeDateRequest.Builder builder =
                                DomesticFundShareFetchByTradeDateRequest.newBuilder();
                        if (tsCode != null && !tsCode.isBlank()) {
                            builder.setTsCode(tsCode);
                        }
                        if (tradeDate != null && !tradeDate.isBlank()) {
                            builder.setTradeDate(tradeDate);
                        }
                        if (startDate != null && !startDate.isBlank()) {
                            builder.setStartDate(startDate);
                        }
                        if (endDate != null && !endDate.isBlank()) {
                            builder.setEndDate(endDate);
                        }
                        if (market != null && !market.isBlank()) {
                            builder.setMarket(market);
                        }
                        builder.setOffset(offset);
                        builder.setLimit(limit);
                        DomesticFundShareFetchByTradeDateRequest request = builder.build();
                        result = domesticFundFetchService.fetchFundShareByTradeDate(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "etf_share_size":
                    if (taskSubType == 1) {
                        // ETF份额规模，支持多种参数组合
                        String tsCode = taskParams.getString("ts_code");
                        String tradeDate = taskParams.getString("trade_date");
                        String startDate = taskParams.getString("start_date");
                        String endDate = taskParams.getString("end_date");
                        String exchange = taskParams.getString("exchange");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticEtfShareSizeFetchByTradeDateRequest.Builder builder =
                                DomesticEtfShareSizeFetchByTradeDateRequest.newBuilder();
                        if (tsCode != null && !tsCode.isBlank()) {
                            builder.setTsCode(tsCode);
                        }
                        if (tradeDate != null && !tradeDate.isBlank()) {
                            builder.setTradeDate(tradeDate);
                        }
                        if (startDate != null && !startDate.isBlank()) {
                            builder.setStartDate(startDate);
                        }
                        if (endDate != null && !endDate.isBlank()) {
                            builder.setEndDate(endDate);
                        }
                        if (exchange != null && !exchange.isBlank()) {
                            builder.setExchange(exchange);
                        }
                        builder.setOffset(offset);
                        builder.setLimit(limit);
                        result = domesticFundFetchService.fetchEtfShareSizeByTradeDate(builder.build()).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                // ==================== 新增股票接口 ====================

                case "stock_income":
                    if (taskSubType == 1) {
                        String period = taskParams.getString("period");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticStockIncomeFetchByPeriodRequest request =
                                DomesticStockIncomeFetchByPeriodRequest.newBuilder()
                                        .setPeriod(period)
                                        .setOffset(offset)
                                        .setLimit(limit)
                                        .build();
                        result = domesticStockFetchService.fetchStockIncomeByPeriod(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_balancesheet":
                    if (taskSubType == 1) {
                        String period = taskParams.getString("period");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticStockBalancesheetFetchByPeriodRequest request =
                                DomesticStockBalancesheetFetchByPeriodRequest.newBuilder()
                                        .setPeriod(period)
                                        .setOffset(offset)
                                        .setLimit(limit)
                                        .build();
                        result = domesticStockFetchService.fetchStockBalancesheetByPeriod(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_cashflow":
                    if (taskSubType == 1) {
                        String period = taskParams.getString("period");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticStockCashflowFetchByPeriodRequest request =
                                DomesticStockCashflowFetchByPeriodRequest.newBuilder()
                                        .setPeriod(period)
                                        .setOffset(offset)
                                        .setLimit(limit)
                                        .build();
                        result = domesticStockFetchService.fetchStockCashflowByPeriod(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_forecast":
                    if (taskSubType == 1) {
                        String startDate = taskParams.getString("start_date");
                        String endDate = taskParams.getString("end_date");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticStockForecastFetchByDateRangeRequest request =
                                DomesticStockForecastFetchByDateRangeRequest.newBuilder()
                                        .setStartDate(startDate)
                                        .setEndDate(endDate)
                                        .setOffset(offset)
                                        .setLimit(limit)
                                        .build();
                        result = domesticStockFetchService.fetchStockForecastByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_express":
                    if (taskSubType == 1) {
                        String startDate = taskParams.getString("start_date");
                        String endDate = taskParams.getString("end_date");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticStockExpressFetchByDateRangeRequest request =
                                DomesticStockExpressFetchByDateRangeRequest.newBuilder()
                                        .setStartDate(startDate)
                                        .setEndDate(endDate)
                                        .setOffset(offset)
                                        .setLimit(limit)
                                        .build();
                        result = domesticStockFetchService.fetchStockExpressByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_report_rc":
                    if (taskSubType == 1) {
                        String startDate = taskParams.getString("start_date");
                        String endDate = taskParams.getString("end_date");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticStockReportRcFetchByDateRangeRequest request =
                                DomesticStockReportRcFetchByDateRangeRequest.newBuilder()
                                        .setStartDate(startDate)
                                        .setEndDate(endDate)
                                        .setOffset(offset)
                                        .setLimit(limit)
                                        .build();
                        result = domesticStockFetchService.fetchStockReportRcByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_moneyflow":
                    if (taskSubType == 1) {
                        String tradeDate = taskParams.getString("trade_date");
                        DomesticStockMoneyflowFetchByTradeDateRequest request =
                                DomesticStockMoneyflowFetchByTradeDateRequest.newBuilder()
                                        .setTradeDate(tradeDate)
                                        .build();
                        result = domesticStockFetchService.fetchStockMoneyflowByTradeDate(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_top10_holders":
                    if (taskSubType == 1) {
                        String tsCode = taskParams.getString("ts_code");
                        String startDate = taskParams.getString("start_date");
                        String endDate = taskParams.getString("end_date");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticStockTop10HoldersFetchByTsCodeRequest request =
                                DomesticStockTop10HoldersFetchByTsCodeRequest.newBuilder()
                                        .setTsCode(tsCode)
                                        .setStartDate(startDate != null ? startDate : "")
                                        .setEndDate(endDate != null ? endDate : "")
                                        .setOffset(offset)
                                        .setLimit(limit)
                                        .build();
                        result = domesticStockFetchService.fetchStockTop10HoldersByTsCode(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_share_float":
                    if (taskSubType == 1) {
                        String startDate = taskParams.getString("start_date");
                        String endDate = taskParams.getString("end_date");
                        int offset = taskParams.getIntValue("offset");
                        int limit = taskParams.getIntValue("limit");
                        DomesticStockShareFloatFetchByDateRangeRequest request =
                                DomesticStockShareFloatFetchByDateRangeRequest.newBuilder()
                                        .setStartDate(startDate)
                                        .setEndDate(endDate)
                                        .setOffset(offset)
                                        .setLimit(limit)
                                        .build();
                        result = domesticStockFetchService.fetchStockShareFloatByDateRange(request).getFetchedItemsCount();
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

package world.willfrog.alphafrogmicro.domestic.fetch;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rabbitmq.client.Channel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.fetch.config.DomesticFetchRabbitConfig;
import world.willfrog.alphafrogmicro.domestic.fetch.rag.RagAnnouncementFetchJob;
import world.willfrog.alphafrogmicro.domestic.fetch.rag.RagResearchReportFetchJob;
import world.willfrog.alphafrogmicro.domestic.idl.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RabbitMQ 抓取任务消费端。
 * 接收 FETCH_TASK_QUEUE 的消息后立刻 ACK，转交内部线程池异步执行，
 * 避免阻塞 RabbitMQ Listener 导致超时。
 *
 * 参数解析已统一委托给 {@link FetchTaskParamResolver}，按 JSON Catalog 配置自动提取、
 * 转换并应用默认值。日期参数在 Catalog 中统一为 yyyyMMdd 字符串；对于仍依赖 long 时间戳的
 * 旧 Dubbo 接口，本类通过 {@link #dateToTs(Object)} 做兼容转换。
 */
@Service
@Slf4j
public class FetchTopicConsumer {

    private final DomesticIndexFetchServiceImpl domesticIndexFetchService;
    private final DomesticFundFetchServiceImpl domesticFundFetchService;
    private final DomesticStockFetchServiceImpl domesticStockFetchService;
    private final DomesticTradeCalendarFetchService domesticTradeCalendarFetchService;
    private final RagAnnouncementFetchJob annJob;
    private final RagResearchReportFetchJob reportJob;
    private final RabbitTemplate rabbitTemplate;
    private final FetchTaskParamResolver paramResolver;
    private final TushareRequestTraceService traceService;

    // 异步任务执行线程池
    private ExecutorService taskExecutor;
    // 跟踪正在执行的任务，便于任务完成后清理
    private final ConcurrentHashMap<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    public FetchTopicConsumer(DomesticIndexFetchServiceImpl domesticIndexFetchService,
                              DomesticFundFetchServiceImpl domesticFundFetchService,
                              DomesticStockFetchServiceImpl domesticStockFetchService,
                              DomesticTradeCalendarFetchService domesticTradeCalendarFetchService,
                              RagAnnouncementFetchJob annJob,
                              RagResearchReportFetchJob reportJob,
                              RabbitTemplate rabbitTemplate,
                              FetchTaskParamResolver paramResolver,
                              TushareRequestTraceService traceService) {
        this.domesticIndexFetchService = domesticIndexFetchService;
        this.domesticFundFetchService = domesticFundFetchService;
        this.domesticStockFetchService = domesticStockFetchService;
        this.domesticTradeCalendarFetchService = domesticTradeCalendarFetchService;
        this.annJob = annJob;
        this.reportJob = reportJob;
        this.rabbitTemplate = rabbitTemplate;
        this.paramResolver = paramResolver;
        this.traceService = traceService;
    }

    @PostConstruct
    public void init() {
        // 创建线程池：核心线程数 2，最大线程数 4，队列容量 100
        this.taskExecutor = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadFactory() {
                    private final AtomicInteger count = new AtomicInteger(0);
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "fetch-task-executor-" + count.incrementAndGet());
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        log.info("Fetch task executor initialized");
    }

    @PreDestroy
    public void shutdown() {
        if (taskExecutor != null && !taskExecutor.isShutdown()) {
            log.info("Shutting down fetch task executor...");
            taskExecutor.shutdown();
            try {
                if (!taskExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                    taskExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                taskExecutor.shutdownNow();
            }
        }
    }

    /**
     * RabbitMQ 监听入口。收到消息后立刻 ACK，然后把实际处理逻辑提交到线程池。
     * 这样可以避免单条任务执行时间过长导致 RabbitMQ 消费者超时。
     */
    @RabbitListener(queues = DomesticFetchRabbitConfig.FETCH_TASK_QUEUE)
    public void listenFetchTask(String message,
                                Channel channel,
                                @Header(AmqpHeaders.DELIVERY_TAG) long tag){
        log.debug("Received fetch task: {}", message);

        String taskUuid = null;
        
        try {
            JSONObject rawMessageJSON = JSONObject.parseObject(message);
            if (rawMessageJSON == null) {
                log.error("Invalid message JSON payload");
                channel.basicNack(tag, false, false);
                return;
            }
            taskUuid = rawMessageJSON.getString("task_uuid");
            
            // 立即确认消息，避免 RabbitMQ 超时
            channel.basicAck(tag, false);
            
            // 提交异步任务
            final String finalTaskUuid = taskUuid;
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> processFetchTask(message, finalTaskUuid), taskExecutor);
            
            if (taskUuid != null) {
                runningTasks.put(taskUuid, future);
                future.whenComplete((result, ex) -> runningTasks.remove(finalTaskUuid));
            }
            
        } catch (Exception e) {
            log.error("Failed to process fetch task message", e);
            try {
                channel.basicNack(tag, false, false);
            } catch (Exception nackEx) {
                log.error("Failed to nack message", nackEx);
            }
        }
    }
    
    /**
     * 实际处理抓取任务的逻辑（在独立线程中执行）。
     * 根据 task_name + task_sub_type 路由到对应的 Dubbo 服务或本地 Job。
     */
    private void processFetchTask(String message, String taskUuid) {
        String taskName = null;
        Integer taskSubTypeValue = null;
        boolean success = false;

        try {
            traceService.start(taskUuid);
            JSONObject rawMessageJSON = JSONObject.parseObject(message);
            if (rawMessageJSON == null) {
                throw new IllegalArgumentException("Invalid message JSON payload");
            }
            // 如果外部传入的 taskUuid 为空，尝试从消息中解析
            if (taskUuid == null) {
                taskUuid = rawMessageJSON.getString("task_uuid");
            }
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

            // 统一按 JSON Catalog 解析参数（提取默认值、类型转换）
            Map<String, Object> p = paramResolver.resolve(taskName, taskSubType, taskParams);

            switch (taskName) {
                case "index_info":
                    if (taskSubType == 1) {
                        DomesticIndexInfoFetchByMarketRequest request =
                                DomesticIndexInfoFetchByMarketRequest.newBuilder()
                                        .setMarket(str(p, "market"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticIndexFetchService.fetchDomesticIndexInfoByMarket(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "index_quote":
                    if (taskSubType == 1) {
                        long tradeDate = dateToTs(p.get("trade_date"));
                        DomesticIndexDailyFetchByTradeDateRequest request =
                                DomesticIndexDailyFetchByTradeDateRequest.newBuilder()
                                        .setTradeDate(tradeDate)
                                        .setIndexOffset(num(p, "index_offset"))
                                        .setIndexLimit(indexBatchLimit(p))
                                        .setApiOffsetStart(num(p, "api_offset_start"))
                                        .setApiOffsetEnd(num(p, "api_offset_end"))
                                        .setApiOffsetStep(num(p, "api_offset_step"))
                                        .build();
                        result = domesticIndexFetchService.fetchDomesticIndexDailyByTradeDate(request).getFetchedItemsCount();
                    } else if (taskSubType == 2 || taskSubType == 3) {
                        long startDate = dateToTs(p.get("start_date"));
                        long endDate = dateToTs(p.get("end_date"));
                        DomesticindexDailyFetchAllByDateRangeRequest request =
                                DomesticindexDailyFetchAllByDateRangeRequest.newBuilder()
                                        .setStartDate(startDate).setEndDate(endDate)
                                        .setIndexOffset(num(p, "index_offset"))
                                        .setIndexLimit(indexBatchLimit(p))
                                        .setApiOffsetStart(num(p, "api_offset_start"))
                                        .setApiOffsetEnd(num(p, "api_offset_end"))
                                        .setApiOffsetStep(num(p, "api_offset_step"))
                                        .build();
                        result = domesticIndexFetchService.fetchDomesticIndexDailyAllByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "index_weight":
                    if (taskSubType == 1 || taskSubType == 3) {
                        long startDate = dateToTs(p.get("start_date"));
                        long endDate = dateToTs(p.get("end_date"));
                        DomesticIndexWeightFetchByDateRangeRequest request =
                                DomesticIndexWeightFetchByDateRangeRequest.newBuilder()
                                        .setStartDate(startDate).setEndDate(endDate)
                                        .setIndexOffset(num(p, "index_offset"))
                                        .setIndexLimit(indexBatchLimit(p))
                                        .setApiOffsetStart(num(p, "api_offset_start"))
                                        .setApiOffsetEnd(num(p, "api_offset_end"))
                                        .setApiOffsetStep(num(p, "api_offset_step"))
                                        .build();
                        result = domesticIndexFetchService.fetchDomesticIndexWeightByDateRange(request).getFetchedItemsCount();
                    } else if (taskSubType == 4) {
                        long startDate = dateToTs(p.get("start_date"));
                        long endDate = dateToTs(p.get("end_date"));
                        DomesticIndexWeightFetchByDateRangeRequest request =
                                DomesticIndexWeightFetchByDateRangeRequest.newBuilder()
                                        .setStartDate(startDate).setEndDate(endDate)
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticIndexFetchService.fetchDomesticIndexWeightDirectByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "index_daily_basic":
                    if (taskSubType == 1) {
                        DomesticIndexDailyBasicFetchByTsCodeRequest request =
                                DomesticIndexDailyBasicFetchByTsCodeRequest.newBuilder()
                                        .setTsCode(str(p, "ts_code"))
                                        .setStartDate(str(p, "start_date"))
                                        .setEndDate(str(p, "end_date"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticIndexFetchService.fetchIndexDailyBasicByTsCode(request).getFetchedItemsCount();
                    } else if (taskSubType == 2) {
                        DomesticIndexDailyBasicFetchByTradeDateRequest request =
                                DomesticIndexDailyBasicFetchByTradeDateRequest.newBuilder()
                                        .setTradeDate(str(p, "trade_date"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticIndexFetchService.fetchIndexDailyBasicByTradeDate(request).getFetchedItemsCount();
                    } else if (taskSubType == 3) {
                        DomesticIndexDailyBasicFetchAllByDateRangeRequest request =
                                DomesticIndexDailyBasicFetchAllByDateRangeRequest.newBuilder()
                                        .setStartDate(str(p, "start_date"))
                                        .setEndDate(str(p, "end_date"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticIndexFetchService.fetchIndexDailyBasicAllByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "sw_industry_classify":
                    if (taskSubType == 1) {
                        DomesticSwIndustryClassifyFetchRequest request =
                                DomesticSwIndustryClassifyFetchRequest.newBuilder()
                                        .setLevel(str(p, "level"))
                                        .setSrc(str(p, "src"))
                                        .build();
                        result = domesticIndexFetchService.fetchSwIndustryClassify(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "sw_industry_member":
                    if (taskSubType == 1) {
                        DomesticSwIndustryMemberFetchByL1CodeRequest request =
                                DomesticSwIndustryMemberFetchByL1CodeRequest.newBuilder()
                                        .setL1Code(str(p, "l1_code"))
                                        .setL2Code(str(p, "l2_code"))
                                        .setL3Code(str(p, "l3_code"))
                                        .setTsCode(str(p, "ts_code"))
                                        .setIsNew(str(p, "is_new"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticIndexFetchService.fetchSwIndustryMemberByL1Code(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "sw_industry_daily":
                    if (taskSubType == 1) {
                        DomesticSwIndustryDailyFetchByTradeDateRequest request =
                                DomesticSwIndustryDailyFetchByTradeDateRequest.newBuilder()
                                        .setTradeDate(str(p, "trade_date"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticIndexFetchService.fetchSwIndustryDailyByTradeDate(request).getFetchedItemsCount();
                    } else if (taskSubType == 2) {
                        DomesticSwIndustryDailyFetchByTsCodeRequest request =
                                DomesticSwIndustryDailyFetchByTsCodeRequest.newBuilder()
                                        .setTsCode(str(p, "ts_code"))
                                        .setStartDate(str(p, "start_date"))
                                        .setEndDate(str(p, "end_date"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticIndexFetchService.fetchSwIndustryDailyByTsCode(request).getFetchedItemsCount();
                    } else if (taskSubType == 3) {
                        DomesticSwIndustryDailyFetchAllByDateRangeRequest request =
                                DomesticSwIndustryDailyFetchAllByDateRangeRequest.newBuilder()
                                        .setStartDate(str(p, "start_date"))
                                        .setEndDate(str(p, "end_date"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticIndexFetchService.fetchSwIndustryDailyAllByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "ci_index_member":
                    if (taskSubType == 1) {
                        DomesticCiIndexMemberFetchRequest request =
                                DomesticCiIndexMemberFetchRequest.newBuilder()
                                        .setL1Code(str(p, "l1_code"))
                                        .setL2Code(str(p, "l2_code"))
                                        .setL3Code(str(p, "l3_code"))
                                        .setTsCode(str(p, "ts_code"))
                                        .setIsNew(str(p, "is_new"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticIndexFetchService.fetchCiIndexMember(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "ci_industry_daily":
                    if (taskSubType == 1) {
                        DomesticCiIndustryDailyFetchRequest request =
                                DomesticCiIndustryDailyFetchRequest.newBuilder()
                                        .setTradeDate(str(p, "trade_date"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticIndexFetchService.fetchCiIndustryDaily(request).getFetchedItemsCount();
                    } else if (taskSubType == 2) {
                        DomesticCiIndustryDailyFetchRequest request =
                                DomesticCiIndustryDailyFetchRequest.newBuilder()
                                        .setTsCode(str(p, "ts_code"))
                                        .setStartDate(str(p, "start_date"))
                                        .setEndDate(str(p, "end_date"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticIndexFetchService.fetchCiIndustryDaily(request).getFetchedItemsCount();
                    } else if (taskSubType == 3) {
                        DomesticCiIndustryDailyFetchAllByDateRangeRequest request =
                                DomesticCiIndustryDailyFetchAllByDateRangeRequest.newBuilder()
                                        .setStartDate(str(p, "start_date"))
                                        .setEndDate(str(p, "end_date"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticIndexFetchService.fetchCiIndustryDailyAllByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "fund_info":
                    if (taskSubType == 1) {
                        DomesticFundInfoFetchByMarketRequest request =
                                DomesticFundInfoFetchByMarketRequest.newBuilder()
                                        .setMarket(str(p, "market"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticFundFetchService.fetchDomesticFundInfoByMarket(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "fund_nav":
                    if (taskSubType == 1) {
                        long tradeDate = dateToTs(p.get("trade_date"));
                        DomesticFundNavFetchByTradeDateRequest request =
                                DomesticFundNavFetchByTradeDateRequest.newBuilder()
                                        .setTradeDateTimestamp(tradeDate)
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticFundFetchService.fetchDomesticFundNavByTradeDate(request).getFetchedItemsCount();
                        Thread.sleep(200);
                    } else if (taskSubType == 2) {
                        long startDate = dateToTs(p.get("start_date"));
                        long endDate = dateToTs(p.get("end_date"));
                        DomesticFundNavFetchByDateRangeRequest request =
                                DomesticFundNavFetchByDateRangeRequest.newBuilder()
                                        .setStartDateTimestamp(startDate)
                                        .setEndDateTimestamp(endDate)
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticFundFetchService.fetchDomesticFundNavByDateRange(request).getFetchedItemsCount();
                        Thread.sleep(200);
                    } else {
                        result = -1;
                    }
                    break;

                case "fund_portfolio":
                    if (taskSubType == 1) {
                        long startDate = dateToTs(p.get("start_date"));
                        long endDate = dateToTs(p.get("end_date"));
                        DomesticFundPortfolioFetchByDateRangeRequest request =
                                DomesticFundPortfolioFetchByDateRangeRequest.newBuilder()
                                        .setStartDateTimestamp(startDate)
                                        .setEndDateTimestamp(endDate)
                                        .setTsCode(str(p, "ts_code"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticFundFetchService.fetchDomesticFundPortfolioByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "fund_company":
                    if (taskSubType == 1) {
                        result = domesticFundFetchService.fetchFundCompany(
                                DomesticFundCompanyFetchRequest.newBuilder().build()
                        ).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "fund_manager":
                    if (taskSubType == 1) {
                        DomesticFundManagerFetchByTsCodeRequest request =
                                DomesticFundManagerFetchByTsCodeRequest.newBuilder()
                                        .setTsCode(str(p, "ts_code"))
                                        .setAnnDate(str(p, "ann_date"))
                                        .setName(str(p, "name"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticFundFetchService.fetchFundManagerByTsCode(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "fund_share":
                    if (taskSubType == 1 || taskSubType == 3) {
                        DomesticFundShareFetchByTradeDateRequest request =
                                DomesticFundShareFetchByTradeDateRequest.newBuilder()
                                        .setTsCode(str(p, "ts_code"))
                                        .setTradeDate(str(p, "trade_date"))
                                        .setStartDate(str(p, "start_date"))
                                        .setEndDate(str(p, "end_date"))
                                        .setMarket(str(p, "market"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticFundFetchService.fetchFundShareByTradeDate(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "etf_share_size":
                    if (taskSubType == 1 || taskSubType == 3) {
                        DomesticEtfShareSizeFetchByTradeDateRequest request =
                                DomesticEtfShareSizeFetchByTradeDateRequest.newBuilder()
                                        .setTsCode(str(p, "ts_code"))
                                        .setTradeDate(str(p, "trade_date"))
                                        .setStartDate(str(p, "start_date"))
                                        .setEndDate(str(p, "end_date"))
                                        .setExchange(str(p, "exchange"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticFundFetchService.fetchEtfShareSizeByTradeDate(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "trade_calendar":
                    if (taskSubType == 1) {
                        long startDate = dateToTs(p.get("start_date"));
                        long endDate = dateToTs(p.get("end_date"));
                        DomesticTradeCalendarFetchByDateRangeRequest request =
                                DomesticTradeCalendarFetchByDateRangeRequest.newBuilder()
                                        .setStartDate(startDate).setEndDate(endDate)
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticTradeCalendarFetchService.fetchDomesticTradeCalendarByDateRange(request)
                                .getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_info":
                    if (taskSubType == 1) {
                        DomesticStockInfoFetchByMarketRequest request =
                                DomesticStockInfoFetchByMarketRequest.newBuilder()
                                        .setMarket(str(p, "market"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticStockFetchService.fetchStockInfoByMarket(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_daily":
                    if (taskSubType == 1) {
                        long tradeDate = dateToTs(p.get("trade_date"));
                        DomesticStockDailyFetchByTradeDateRequest request =
                                DomesticStockDailyFetchByTradeDateRequest.newBuilder()
                                        .setTradeDate(tradeDate)
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticStockFetchService.fetchStockDailyByTradeDate(request).getFetchedItemsCount();
                    } else if (taskSubType == 3) {
                        DomesticStockDailyFetchAllByDateRangeRequest request =
                                DomesticStockDailyFetchAllByDateRangeRequest.newBuilder()
                                        .setStartDate(str(p, "start_date"))
                                        .setEndDate(str(p, "end_date"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticStockFetchService.fetchStockDailyAllByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_quote":
                    if (taskSubType == 1) {
                        long startDate = dateToTs(p.get("start_date"));
                        long endDate = dateToTs(p.get("end_date"));
                        result = domesticStockFetchService.fetchStockDailyByDateRange(
                                startDate, endDate, num(p, "offset"), num(p, "limit"));
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_income":
                    if (taskSubType == 1) {
                        DomesticStockIncomeFetchByPeriodRequest request =
                                DomesticStockIncomeFetchByPeriodRequest.newBuilder()
                                        .setPeriod(str(p, "period"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticStockFetchService.fetchStockIncomeByPeriod(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_balancesheet":
                    if (taskSubType == 1) {
                        DomesticStockBalancesheetFetchByPeriodRequest request =
                                DomesticStockBalancesheetFetchByPeriodRequest.newBuilder()
                                        .setPeriod(str(p, "period"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticStockFetchService.fetchStockBalancesheetByPeriod(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_cashflow":
                    if (taskSubType == 1) {
                        DomesticStockCashflowFetchByPeriodRequest request =
                                DomesticStockCashflowFetchByPeriodRequest.newBuilder()
                                        .setPeriod(str(p, "period"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticStockFetchService.fetchStockCashflowByPeriod(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_forecast":
                    if (taskSubType == 1) {
                        DomesticStockForecastFetchByDateRangeRequest request =
                                DomesticStockForecastFetchByDateRangeRequest.newBuilder()
                                        .setStartDate(str(p, "start_date"))
                                        .setEndDate(str(p, "end_date"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticStockFetchService.fetchStockForecastByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_express":
                    if (taskSubType == 1) {
                        DomesticStockExpressFetchByDateRangeRequest request =
                                DomesticStockExpressFetchByDateRangeRequest.newBuilder()
                                        .setStartDate(str(p, "start_date"))
                                        .setEndDate(str(p, "end_date"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticStockFetchService.fetchStockExpressByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_report_rc":
                    if (taskSubType == 1) {
                        DomesticStockReportRcFetchByDateRangeRequest request =
                                DomesticStockReportRcFetchByDateRangeRequest.newBuilder()
                                        .setStartDate(str(p, "start_date"))
                                        .setEndDate(str(p, "end_date"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticStockFetchService.fetchStockReportRcByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_moneyflow":
                    if (taskSubType == 1 || taskSubType == 3) {
                        DomesticStockMoneyflowFetchByTradeDateRequest request =
                                DomesticStockMoneyflowFetchByTradeDateRequest.newBuilder()
                                        .setTradeDate(str(p, "trade_date"))
                                        .setStartDate(str(p, "start_date"))
                                        .setEndDate(str(p, "end_date"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticStockFetchService.fetchStockMoneyflowByTradeDate(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_top10_holders":
                    if (taskSubType == 1) {
                        DomesticStockTop10HoldersFetchByTsCodeRequest request =
                                DomesticStockTop10HoldersFetchByTsCodeRequest.newBuilder()
                                        .setTsCode(str(p, "ts_code"))
                                        .setStartDate(str(p, "start_date"))
                                        .setEndDate(str(p, "end_date"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticStockFetchService.fetchStockTop10HoldersByTsCode(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "stock_share_float":
                    if (taskSubType == 1) {
                        DomesticStockShareFloatFetchByDateRangeRequest request =
                                DomesticStockShareFloatFetchByDateRangeRequest.newBuilder()
                                        .setStartDate(str(p, "start_date"))
                                        .setEndDate(str(p, "end_date"))
                                        .setOffset(num(p, "offset"))
                                        .setLimit(num(p, "limit"))
                                        .build();
                        result = domesticStockFetchService.fetchStockShareFloatByDateRange(request).getFetchedItemsCount();
                    } else {
                        result = -1;
                    }
                    break;

                case "rag_ann_fetch": {
                    int offsetParam = num(p, "offset");
                    int limitParam = num(p, "limit");
                    int pageLimit = limitParam > 0 ? limitParam : RagAnnouncementFetchJob.DEFAULT_PAGE_LIMIT;
                    result = annJob.fetchRange(str(p, "start_date"), str(p, "end_date"), str(p, "title_filter"), offsetParam, pageLimit);
                    break;
                }
                case "rag_report_fetch": {
                    JSONArray indArr = taskParams.getJSONArray("industries");
                    List<String> industries = indArr != null ? indArr.toJavaList(String.class) : List.of();
                    int offsetParam = num(p, "offset");
                    int limitParam = num(p, "limit");
                    int pageLimit = limitParam > 0 ? limitParam : RagResearchReportFetchJob.DEFAULT_PAGE_LIMIT;
                    result = reportJob.fetchRange(str(p, "start_date"), str(p, "end_date"), industries, offsetParam, pageLimit);
                    break;
                }

                default:
                    result = -2;
                    break;
            }
            log.info("Task [{}] result: {}", taskUuid, result);
            if (result < 0) {
                String failMsg = "Task execution failed with result code " + result;
                sendTaskResult(taskUuid, taskName, taskSubTypeValue, result, failMsg);
                success = false;
            } else {
                sendTaskResult(taskUuid, taskName, taskSubTypeValue, result, null);
                success = true;
            }
        } catch (Exception e) {
            log.error("Failed to execute task [{}]: {}", taskUuid, message, e);
            if (taskUuid != null && !taskUuid.isBlank()) {
                sendTaskResult(taskUuid, taskName, taskSubTypeValue, -1, e.getMessage());
            }
        } finally {
            if (taskUuid != null) {
                if (!success) {
                    traceService.persistOnFailure(taskUuid);
                }
                traceService.clear();
                runningTasks.remove(taskUuid);
            }
            log.info("Task [{}] execution completed, success={}", taskUuid, success);
        }
    }

    /** 将 yyyyMMdd 字符串或已有的 long/Number 转换为毫秒时间戳。解析失败时记录警告并返回 0L。 */
    private long dateToTs(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        String s = value.toString().trim();
        if (s.isEmpty()) return 0L;
        if (s.matches("\\d{8}")) {
            Long ts = DateConvertUtils.convertDateStrToLong(s, "yyyyMMdd");
            if (ts != null && ts >= 0) return ts;
            log.warn("dateToTs 解析失败: yyyyMMdd 格式转换异常, 输入='{}'", s);
            return 0L;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            log.warn("dateToTs 解析失败: 无法将 '{}' 转换为时间戳", s);
            return 0L;
        }
    }

    /**
     * 本地指数每批条数：与 Catalog / 展开层字段名 {@code index_batch_limit} 一致；
     * 兼容历史 payload 中的 {@code index_limit} 以及与 TuShare 分页同名的 {@code limit}（旧任务）。
     */
    private int indexBatchLimit(Map<String, Object> p) {
        if (p.get("index_batch_limit") != null) {
            return num(p, "index_batch_limit");
        }
        if (p.get("index_limit") != null) {
            return num(p, "index_limit");
        }
        return num(p, "limit");
    }

    /** 安全获取字符串，null 时返回空串。 */
    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    /** 安全获取整数，null 或非数字时返回 0。 */
    private int num(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /** 通过 RabbitMQ 发送任务执行结果，供 frontend 或其他服务消费并更新数据库状态 */
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

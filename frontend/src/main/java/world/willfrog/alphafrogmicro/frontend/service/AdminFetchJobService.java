package world.willfrog.alphafrogmicro.frontend.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.alphafrogmicro.common.dao.agent.AdminFetchJobDao;
import world.willfrog.alphafrogmicro.common.dao.agent.AdminFetchTaskDao;
import world.willfrog.alphafrogmicro.common.pojo.agent.AdminFetchJob;
import world.willfrog.alphafrogmicro.common.pojo.agent.AdminFetchTask;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradeCalendarFetchByDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradeCalendarFetchService;
import world.willfrog.alphafrogmicro.frontend.config.TaskProducerRabbitConfig;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * admin 抓取任务批次（Job）服务
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AdminFetchJobService {

    private final AdminFetchJobDao adminFetchJobDao;
    private final AdminFetchTaskDao adminFetchTaskDao;
    private final RabbitTemplate rabbitTemplate;
    private final FetchTaskStatusService fetchTaskStatusService;
    private final RateLimitingService rateLimitingService;
    private final FetchQueueService fetchQueueService;

    @DubboReference
    private DomesticTradeCalendarFetchService domesticTradeCalendarFetchService;

    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int MAX_LEAF_TASKS = 5000;
    private static final int DEFAULT_LIMIT = 5000;

    private static final Set<String> STRING_DATE_TASKS = Set.of(
            "index_daily_basic", "sw_industry_daily", "fund_share", "etf_share_size"
    );
    private static final Set<String> EMPTY_PARAMS_TASKS = Set.of(
            "sw_industry_classify", "sw_industry_member", "ci_index_member", "fund_manager"
    );
    private static final Map<String, String> INFO_TASK_NAME = Map.of(
            "fund", "fund_info",
            "stock", "stock_info",
            "index", "index_info"
    );
    private static final Set<String> VALID_MODES = Set.of("tasks", "task_sets", "fetch_info", "all");

    // ==================== Catalog ====================

    public Map<String, Object> buildCatalog() {
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("taskCatalog", buildTaskCatalog());
        catalog.put("fetchInfoCatalog", buildFetchInfoCatalog());
        catalog.put("quickPresets", buildQuickPresets());
        return catalog;
    }

    private List<Map<String, Object>> buildTaskCatalog() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(taskMeta("stock_daily", "股票日线", List.of(1, 2), List.of("trade_dates", "offsets", "trade_dates_with_offsets", "date_range_with_offsets"), "timestamp", false, stockDailyParams()));
        list.add(taskMeta("stock_quote", "股票行情", List.of(1, 2), List.of("trade_dates", "offsets", "trade_dates_with_offsets", "date_range_with_offsets"), "timestamp", false, commonRangeParams()));
        list.add(taskMeta("index_quote", "指数行情", List.of(1, 2), List.of("trade_dates", "offsets", "trade_dates_with_offsets", "date_range_with_offsets"), "timestamp", false, commonRangeParams()));
        list.add(taskMeta("index_weight", "指数权重", List.of(1), List.of("trade_dates", "offsets", "trade_dates_with_offsets", "date_range_with_offsets"), "timestamp", false, commonRangeParams()));
        list.add(taskMeta("index_daily_basic", "指数日线指标", List.of(1, 2), List.of("trade_dates", "offsets", "trade_dates_with_offsets", "date_range_with_offsets"), "yyyyMMdd", false, commonRangeParams()));
        list.add(taskMeta("fund_nav", "基金净值", List.of(1, 2), List.of("trade_dates", "offsets", "trade_dates_with_offsets", "date_range_with_offsets"), "timestamp", false, commonRangeParams()));
        list.add(taskMeta("fund_portfolio", "基金持仓", List.of(1), List.of("trade_dates", "offsets", "trade_dates_with_offsets", "date_range_with_offsets"), "timestamp", false, commonRangeParams()));
        list.add(taskMeta("fund_share", "基金份额", List.of(1), List.of("trade_dates", "offsets", "trade_dates_with_offsets", "date_range_with_offsets"), "yyyyMMdd", false, commonRangeParams()));
        list.add(taskMeta("etf_share_size", "ETF 份额规模", List.of(1), List.of("trade_dates", "offsets", "trade_dates_with_offsets", "date_range_with_offsets"), "yyyyMMdd", false, commonRangeParams()));
        list.add(taskMeta("trade_calendar", "交易日历", List.of(1), List.of("date_range_with_offsets"), "timestamp", false, commonRangeParams()));
        list.add(taskMeta("sw_industry_daily", "申万行业日线", List.of(1), List.of("trade_dates", "offsets", "trade_dates_with_offsets", "date_range_with_offsets"), "yyyyMMdd", false, commonRangeParams()));
        list.add(taskMeta("sw_industry_classify", "申万行业分类", List.of(1), List.of(), "", true, List.of()));
        list.add(taskMeta("sw_industry_member", "申万行业成分", List.of(1), List.of(), "", true, List.of()));
        list.add(taskMeta("ci_index_member", "中证指数成分", List.of(1), List.of(), "", true, List.of()));
        list.add(taskMeta("fund_manager", "基金经理", List.of(1), List.of(), "", true, List.of()));
        list.add(taskMeta("fund_info", "基金基本信息", List.of(1), List.of(), "", false, infoParams()));
        list.add(taskMeta("stock_info", "股票基本信息", List.of(1), List.of(), "", false, infoParams()));
        list.add(taskMeta("index_info", "指数基本信息", List.of(1), List.of(), "", false, infoParams()));
        return list;
    }

    private Map<String, Object> taskMeta(String taskName, String label, List<Integer> subTypes,
                                         List<String> taskSetModes, String dateStyle,
                                         boolean acceptsEmpty, List<Map<String, Object>> paramsSchema) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskName", taskName);
        map.put("label", label);
        map.put("supportedSubTypes", subTypes);
        map.put("taskSetModes", taskSetModes);
        map.put("dateStyle", dateStyle);
        map.put("acceptsEmptyTaskParams", acceptsEmpty);
        map.put("paramsSchema", paramsSchema);
        map.put("defaultParams", Map.of("offset", 0, "limit", 5000));
        return map;
    }

    private List<Map<String, Object>> commonRangeParams() {
        return List.of(
                paramSchema("offset", "偏移量", "number", false, "0", 0),
                paramSchema("limit", "每页条数", "number", false, "5000", 5000),
                paramSchema("trade_date_timestamp", "交易日时间戳", "number", false, "", null),
                paramSchema("trade_date", "交易日(YYYYMMDD)", "string", false, "", null),
                paramSchema("start_date_timestamp", "开始日期时间戳", "number", false, "", null),
                paramSchema("end_date_timestamp", "结束日期时间戳", "number", false, "", null),
                paramSchema("start_date", "开始日期", "string", false, "", null),
                paramSchema("end_date", "结束日期", "string", false, "", null)
        );
    }

    private List<Map<String, Object>> stockDailyParams() {
        return List.of(
                paramSchema("offset", "偏移量", "number", false, "0", 0),
                paramSchema("limit", "每页条数", "number", false, "5000", 5000),
                paramSchema("trade_date_timestamp", "交易日时间戳", "number", false, "", null),
                paramSchema("start_date_timestamp", "开始日期时间戳", "number", false, "", null),
                paramSchema("end_date_timestamp", "结束日期时间戳", "number", false, "", null)
        );
    }

    private List<Map<String, Object>> infoParams() {
        return List.of(
                paramSchema("offset", "偏移量", "number", false, "0", 0),
                paramSchema("limit", "每页条数", "number", false, "5000", 5000),
                paramSchema("market", "市场", "string", false, "E", "E")
        );
    }

    private Map<String, Object> paramSchema(String name, String label, String type,
                                            boolean required, String placeholder, Object defaultValue) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("label", label);
        map.put("type", type);
        map.put("required", required);
        map.put("placeholder", placeholder);
        map.put("defaultValue", defaultValue);
        return map;
    }

    private Map<String, Object> buildFetchInfoCatalog() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fund", Map.of("label", "基金信息", "defaultMarket", "E"));
        map.put("stock", Map.of("label", "股票信息", "defaultMarket", "E"));
        map.put("index", Map.of("label", "指数信息", "defaultMarket", "E"));
        return map;
    }

    private List<Map<String, Object>> buildQuickPresets() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(preset("stock_quote_range", "股票行情范围", "task_sets",
                List.of(Map.of("task_name", "stock_quote", "task_sub_type", 1, "task_set_mode", "date_range_with_offsets",
                        "task_params", Map.of("limit", 5000), "date_range", Map.of("start_date", "", "end_date", ""), "offset_range", Map.of("start", 0, "end", 10000, "step", 5000)))));
        list.add(preset("index_quote_trade_date", "指数行情单日", "tasks",
                List.of(Map.of("task_name", "index_quote", "task_sub_type", 1, "task_params", Map.of("trade_date_timestamp", "", "offset", 0, "limit", 5000)))));
        list.add(preset("index_quote_range", "指数行情范围", "task_sets",
                List.of(Map.of("task_name", "index_quote", "task_sub_type", 2, "task_set_mode", "date_range_with_offsets",
                        "task_params", Map.of("limit", 5000), "date_range", Map.of("start_date", "", "end_date", ""), "offset_range", Map.of("start", 0, "end", 10000, "step", 5000)))));
        list.add(preset("index_weight_range", "指数权重范围", "task_sets",
                List.of(Map.of("task_name", "index_weight", "task_sub_type", 1, "task_set_mode", "date_range_with_offsets",
                        "task_params", Map.of("limit", 5000), "date_range", Map.of("start_date", "", "end_date", ""), "offset_range", Map.of("start", 0, "end", 10000, "step", 5000)))));
        list.add(preset("fund_portfolio_range", "基金持仓范围", "task_sets",
                List.of(Map.of("task_name", "fund_portfolio", "task_sub_type", 1, "task_set_mode", "date_range_with_offsets",
                        "task_params", Map.of("limit", 5000), "date_range", Map.of("start_date", "", "end_date", ""), "offset_range", Map.of("start", 0, "end", 10000, "step", 5000)))));
        list.add(preset("trade_calendar_range", "交易日历范围", "task_sets",
                List.of(Map.of("task_name", "trade_calendar", "task_sub_type", 1, "task_set_mode", "date_range_with_offsets",
                        "task_params", Map.of("limit", 5000), "date_range", Map.of("start_date", "", "end_date", ""), "offset_range", Map.of("start", 0, "end", 10000, "step", 5000)))));
        return list;
    }

    private Map<String, Object> preset(String key, String label, String mode, List<Map<String, Object>> items) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", key);
        map.put("label", label);
        map.put("mode", mode);
        if ("tasks".equals(mode)) {
            map.put("tasks", items);
        } else {
            map.put("task_sets", items);
        }
        return map;
    }

    // ==================== Job 创建 ====================

    @Transactional
    public Map<String, Object> createJob(Map<String, Object> body, String createdBy) {
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }

        String mode = getString(body, "mode");
        if (mode == null || !VALID_MODES.contains(mode)) {
            throw new IllegalArgumentException("mode 必须是 tasks / task_sets / fetch_info / all 之一");
        }

        String label = getString(body, "label");
        if (label == null) {
            label = "";
        }

        List<LeafTask> leafTasks = new ArrayList<>();

        if ("tasks".equals(mode) || "all".equals(mode)) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tasks = (List<Map<String, Object>>) body.get("tasks");
            if (tasks != null && !tasks.isEmpty()) {
                leafTasks.addAll(expandRawTasks(tasks, "TASK"));
            }
        }

        if ("task_sets".equals(mode) || "all".equals(mode)) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> taskSets = (List<Map<String, Object>>) body.get("task_sets");
            if (taskSets != null && !taskSets.isEmpty()) {
                leafTasks.addAll(expandTaskSets(taskSets));
            }
        }

        if ("fetch_info".equals(mode) || "all".equals(mode)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fetchInfo = (Map<String, Object>) body.get("fetch_info");
            if (fetchInfo != null && !fetchInfo.isEmpty()) {
                leafTasks.addAll(expandFetchInfo(fetchInfo));
            }
        }

        if (leafTasks.isEmpty()) {
            throw new IllegalArgumentException("没有可执行的任务，请检查请求体中的 tasks / task_sets / fetch_info 配置");
        }

        if (leafTasks.size() > MAX_LEAF_TASKS) {
            throw new IllegalArgumentException("展开后的叶子任务数超过上限 " + MAX_LEAF_TASKS + "，当前 " + leafTasks.size());
        }

        String jobUuid = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();

        // 落库 job
        AdminFetchJob job = new AdminFetchJob();
        job.setJobUuid(jobUuid);
        job.setMode(mode);
        job.setLabel(label);
        job.setStatus("PENDING");
        job.setRequestedSpec(JSONObject.toJSONString(body));
        job.setNormalizedSpec(buildNormalizedSpec(body, leafTasks));
        job.setExpandedTaskCount(leafTasks.size());
        job.setPendingCount(leafTasks.size());
        job.setRunningCount(0);
        job.setSuccessCount(0);
        job.setFailureCount(0);
        job.setCreatedBy(createdBy);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        adminFetchJobDao.insert(job);

        // 落库叶子任务并派发
        List<Map<String, Object>> preview = new ArrayList<>();
        for (LeafTask leaf : leafTasks) {
            String taskUuid = UUID.randomUUID().toString();
            leaf.taskUuid = taskUuid;

            AdminFetchTask task = new AdminFetchTask();
            task.setTaskUuid(taskUuid);
            task.setJobUuid(jobUuid);
            task.setTaskName(leaf.taskName);
            task.setTaskSubType(leaf.taskSubType);
            task.setStatus("PENDING");
            task.setSourceKind(leaf.sourceKind);
            task.setSourceIndex(leaf.sourceIndex);
            task.setTaskSetMode(leaf.taskSetMode);
            task.setParamsSummary(leaf.paramsSummary);
            task.setInputParams(leaf.inputParams);
            task.setDispatchPayload(leaf.dispatchPayload);
            task.setCreatedBy(createdBy);
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            adminFetchTaskDao.insert(task);

            dispatchLeafTask(leaf);

            Map<String, Object> p = new LinkedHashMap<>();
            p.put("taskUuid", taskUuid);
            p.put("taskName", leaf.taskName);
            p.put("taskSubType", leaf.taskSubType);
            p.put("status", "PENDING");
            preview.add(p);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job", convertJobToMap(job));
        result.put("itemsPreview", preview);
        result.put("message", "Job creation request received and is being processed.");
        return result;
    }

    private String buildNormalizedSpec(Map<String, Object> body, List<LeafTask> leafTasks) {
        Map<String, Object> spec = new LinkedHashMap<>(body);
        spec.put("expanded_task_count", leafTasks.size());
        List<Map<String, Object>> expanded = new ArrayList<>();
        for (LeafTask leaf : leafTasks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("task_name", leaf.taskName);
            m.put("task_sub_type", leaf.taskSubType);
            m.put("source_kind", leaf.sourceKind);
            m.put("source_index", leaf.sourceIndex);
            m.put("task_set_mode", leaf.taskSetMode);
            m.put("task_params", leaf.taskParams);
            expanded.add(m);
        }
        spec.put("expanded_tasks", expanded);
        return JSONObject.toJSONString(spec);
    }

    // ==================== 展开逻辑 ====================

    private List<LeafTask> expandRawTasks(List<Map<String, Object>> tasks, String sourceKind) {
        List<LeafTask> result = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            Map<String, Object> raw = tasks.get(i);
            LeafTask leaf = normalizeSingleTask(raw, i);
            leaf.sourceKind = sourceKind;
            leaf.sourceIndex = i;
            leaf.paramsSummary = buildParamsSummary(leaf.taskName, leaf.taskParams);
            leaf.inputParams = JSONObject.toJSONString(raw);
            leaf.dispatchPayload = buildDispatchPayload(leaf.taskUuid, leaf.taskName, leaf.taskSubType, leaf.taskParams);
            result.add(leaf);
        }
        return result;
    }

    private List<LeafTask> expandTaskSets(List<Map<String, Object>> taskSets) {
        List<LeafTask> result = new ArrayList<>();
        for (int i = 0; i < taskSets.size(); i++) {
            result.addAll(expandSingleTaskSet(taskSets.get(i), i));
        }
        return result;
    }

    private List<LeafTask> expandSingleTaskSet(Map<String, Object> rawTask, int index) {
        String taskName = getTaskName(rawTask);
        if (taskName == null || taskName.isBlank()) {
            throw new IllegalArgumentException("task_sets[" + index + "] 缺少 task_name");
        }

        String mode = normalizeTaskSetMode(rawTask);
        int taskSubType = getIntValue(rawTask.get("task_sub_type"), 1);
        Map<String, Object> baseParams = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> taskParams = (Map<String, Object>) rawTask.get("task_params");
        if (taskParams != null) {
            baseParams.putAll(taskParams);
        }
        // 合并顶层非保留字段到 task_params（与 ingestion_flow.py 对齐）
        Set<String> skipKeys = Set.of("task_name", "task_type", "task_sub_type", "task_subtype", "taskSubType",
                "task_params", "trade_dates", "date_range", "task_set_mode", "expand_mode",
                "offset_range", "offset_start", "offset_end", "offset_step", "ingest_token");
        for (Map.Entry<String, Object> entry : rawTask.entrySet()) {
            if (skipKeys.contains(entry.getKey())) continue;
            if (!baseParams.containsKey(entry.getKey())) {
                baseParams.put(entry.getKey(), entry.getValue());
            }
        }
        normalizeParamsInPlace(baseParams);

        List<Map<String, Object>> expandedParamsList = new ArrayList<>();
        boolean useStringDate = STRING_DATE_TASKS.contains(taskName);

        switch (mode) {
            case "trade_dates" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> tradeDates = (Map<String, Object>) rawTask.get("trade_dates");
                List<LocalDate> dates = expandTradeDates(tradeDates, index);
                for (LocalDate d : dates) {
                    Map<String, Object> p = new LinkedHashMap<>(baseParams);
                    if (useStringDate) {
                        p.put("trade_date", dateToString(d));
                    } else {
                        p.put("trade_date_timestamp", dateToTimestampMs(d));
                    }
                    expandedParamsList.add(p);
                }
            }
            case "offsets" -> {
                List<Integer> offsets = expandOffsets(rawTask, index);
                for (int off : offsets) {
                    Map<String, Object> p = new LinkedHashMap<>(baseParams);
                    p.put("offset", off);
                    expandedParamsList.add(p);
                }
            }
            case "date_range_with_offsets" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> dateRange = (Map<String, Object>) rawTask.get("date_range");
                if (dateRange == null) {
                    throw new IllegalArgumentException("task_sets[" + index + "] date_range_with_offsets 模式需要 date_range 配置");
                }
                Object startDateRaw = dateRange.get("start_date");
                Object endDateRaw = dateRange.get("end_date");
                if (startDateRaw == null || endDateRaw == null) {
                    throw new IllegalArgumentException("task_sets[" + index + "] date_range_with_offsets 模式需要 start_date 和 end_date");
                }
                List<Integer> offsets = expandOffsets(rawTask, index);
                for (int off : offsets) {
                    Map<String, Object> p = new LinkedHashMap<>(baseParams);
                    p.put("start_date", String.valueOf(startDateRaw));
                    p.put("end_date", String.valueOf(endDateRaw));
                    p.put("offset", off);
                    if (!p.containsKey("limit")) {
                        p.put("limit", 3000);
                    }
                    expandedParamsList.add(p);
                }
                if (rawTask.get("task_sub_type") == null) {
                    taskSubType = 3;
                }
            }
            case "trade_dates_with_offsets" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> tradeDates = (Map<String, Object>) rawTask.get("trade_dates");
                if (tradeDates == null) {
                    throw new IllegalArgumentException("task_sets[" + index + "] trade_dates_with_offsets 模式需要 trade_dates 配置");
                }
                List<LocalDate> dates = expandTradeDates(tradeDates, index);
                List<Integer> offsets = expandOffsets(rawTask, index);
                for (LocalDate d : dates) {
                    for (int off : offsets) {
                        Map<String, Object> p = new LinkedHashMap<>(baseParams);
                        if (useStringDate) {
                            p.put("trade_date", dateToString(d));
                        } else {
                            p.put("trade_date_timestamp", dateToTimestampMs(d));
                        }
                        p.put("offset", off);
                        expandedParamsList.add(p);
                    }
                }
            }
            default -> throw new IllegalArgumentException("不支持的 task_set_mode: " + mode);
        }

        List<LeafTask> result = new ArrayList<>();
        for (int i = 0; i < expandedParamsList.size(); i++) {
            Map<String, Object> p = expandedParamsList.get(i);
            LeafTask leaf = new LeafTask();
            leaf.taskName = taskName;
            leaf.taskSubType = taskSubType;
            leaf.taskParams = p;
            leaf.sourceKind = "TASK_SET";
            leaf.sourceIndex = index;
            leaf.taskSetMode = mode;
            leaf.paramsSummary = buildParamsSummary(taskName, p);
            leaf.inputParams = JSONObject.toJSONString(rawTask);
            leaf.dispatchPayload = buildDispatchPayload(leaf.taskUuid, taskName, taskSubType, p);
            result.add(leaf);
        }
        return result;
    }

    private List<LeafTask> expandFetchInfo(Map<String, Object> fetchInfoCfg) {
        List<LeafTask> result = new ArrayList<>();
        int idx = 0;
        for (Map.Entry<String, Object> entry : fetchInfoCfg.entrySet()) {
            String infoType = entry.getKey();
            if (!INFO_TASK_NAME.containsKey(infoType)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> settings = entry.getValue() == null ? Map.of() : (Map<String, Object>) entry.getValue();
            boolean enabled = settings.get("enabled") == null || Boolean.TRUE.equals(settings.get("enabled"));
            if (!enabled) {
                continue;
            }
            Object market = settings.get("market");
            int limit = getIntValue(settings.get("limit"), DEFAULT_LIMIT);
            int offset = getIntValue(settings.get("offset"), 0);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("offset", offset);
            params.put("limit", limit);
            if (market != null && !String.valueOf(market).isBlank()) {
                params.put("market", String.valueOf(market));
            }

            LeafTask leaf = new LeafTask();
            leaf.taskName = INFO_TASK_NAME.get(infoType);
            leaf.taskSubType = 1;
            leaf.taskParams = params;
            leaf.sourceKind = "FETCH_INFO";
            leaf.sourceIndex = idx++;
            leaf.paramsSummary = buildParamsSummary(leaf.taskName, params);
            leaf.inputParams = JSONObject.toJSONString(settings);
            leaf.dispatchPayload = buildDispatchPayload(leaf.taskUuid, leaf.taskName, leaf.taskSubType, params);
            result.add(leaf);
        }
        return result;
    }

    private LeafTask normalizeSingleTask(Map<String, Object> raw, int index) {
        String taskName = getTaskName(raw);
        if (taskName == null || taskName.isBlank()) {
            throw new IllegalArgumentException("tasks[" + index + "] 缺少 task_name");
        }
        int taskSubType = getIntValue(raw.get("task_sub_type"), 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> taskParams = raw.get("task_params") == null ? new LinkedHashMap<>() : new LinkedHashMap<>((Map<String, Object>) raw.get("task_params"));

        Set<String> skipKeys = Set.of("task_name", "task_type", "task_sub_type", "task_subtype", "taskSubType", "task_params", "ingest_token");
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (skipKeys.contains(entry.getKey())) continue;
            if (!taskParams.containsKey(entry.getKey())) {
                taskParams.put(entry.getKey(), entry.getValue());
            }
        }
        normalizeParamsInPlace(taskParams);

        if (EMPTY_PARAMS_TASKS.contains(taskName)) {
            // 允许空参数
        } else if (taskParams.isEmpty()) {
            throw new IllegalArgumentException("tasks[" + index + "] task_params 不能为空");
        }

        LeafTask leaf = new LeafTask();
        leaf.taskName = taskName;
        leaf.taskSubType = taskSubType;
        leaf.taskParams = taskParams;
        return leaf;
    }

    // ==================== 日期 / Offset 工具方法 ====================

    private List<LocalDate> expandTradeDates(Map<String, Object> tradeDates, int index) {
        if (tradeDates == null) {
            throw new IllegalArgumentException("task_sets[" + index + "] trade_dates 不能为空");
        }
        LocalDate start = parseDateValue(tradeDates.get("start_timestamp"));
        LocalDate end = parseDateValue(tradeDates.get("end_timestamp"));
        if (start == null || end == null) {
            throw new IllegalArgumentException("task_sets[" + index + "] trade_dates 缺少 start_timestamp 或 end_timestamp");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("task_sets[" + index + "] 结束日期早于开始日期");
        }
        List<LocalDate> dates = new ArrayList<>();
        LocalDate current = start;
        while (!current.isAfter(end)) {
            dates.add(current);
            current = current.plusDays(1);
        }
        return dates;
    }

    private List<Integer> expandOffsets(Map<String, Object> rawTask, int index) {
        @SuppressWarnings("unchecked")
        Map<String, Object> offsetRange = rawTask.get("offset_range") == null ? null : (Map<String, Object>) rawTask.get("offset_range");
        Object start, end, step;
        if (offsetRange != null) {
            start = offsetRange.get("start");
            if (start == null) start = offsetRange.get("offset_start");
            end = offsetRange.get("end");
            if (end == null) end = offsetRange.get("offset_end");
            step = offsetRange.get("step");
            if (step == null) step = offsetRange.get("offset_step");
        } else {
            start = rawTask.get("offset_start");
            end = rawTask.get("offset_end");
            step = rawTask.get("offset_step");
        }
        if (step == null) {
            Object limitValue = rawTask.get("limit");
            if (limitValue == null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> taskParams = rawTask.get("task_params") == null ? null : (Map<String, Object>) rawTask.get("task_params");
                if (taskParams != null) {
                    limitValue = taskParams.get("limit");
                }
            }
            step = limitValue != null ? limitValue : DEFAULT_LIMIT;
        }

        int s = parseIntValue(start, "offset_start", index);
        int e = parseIntValue(end, "offset_end", index);
        int st = parseIntValue(step, "offset_step", index);
        if (st <= 0) {
            throw new IllegalArgumentException("task_sets[" + index + "] offset_step 必须大于 0");
        }
        if (e < s) {
            throw new IllegalArgumentException("task_sets[" + index + "] offset_end 小于 offset_start");
        }
        List<Integer> offsets = new ArrayList<>();
        int current = s;
        while (current <= e) {
            offsets.add(current);
            current += st;
        }
        return offsets;
    }

    private LocalDate parseDateValue(Object value) {
        if (value == null) {
            return null;
        }
        String raw = value.toString().trim();
        if (raw.matches("\\d{8}")) {
            return LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE);
        }
        if (raw.matches("\\d+")) {
            long ts = Long.parseLong(raw);
            if (ts < 10_000_000_000L) {
                ts *= 1000;
            }
            return Instant.ofEpochMilli(ts).atZone(ZONE_SHANGHAI).toLocalDate();
        }
        try {
            return LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private long dateToTimestampMs(LocalDate date) {
        return date.atStartOfDay(ZONE_SHANGHAI).toInstant().toEpochMilli();
    }

    private String dateToString(LocalDate date) {
        return date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private int parseIntValue(Object value, String fieldName, int index) {
        if (value == null) {
            throw new IllegalArgumentException("task_sets[" + index + "] 缺少 " + fieldName);
        }
        try {
            if (value instanceof Number n) {
                return n.intValue();
            }
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("task_sets[" + index + "] " + fieldName + " 必须是整数");
        }
    }

    private String normalizeTaskSetMode(Map<String, Object> rawTask) {
        Object modeRaw = rawTask.get("task_set_mode");
        if (modeRaw == null) {
            modeRaw = rawTask.get("expand_mode");
        }
        if (modeRaw == null) {
            if (rawTask.containsKey("date_range") && (rawTask.containsKey("offset_range") || rawTask.containsKey("offset_start"))) {
                return "date_range_with_offsets";
            }
            if (rawTask.containsKey("offset_range") || rawTask.containsKey("offset_start") || rawTask.containsKey("offset_end")) {
                if (rawTask.containsKey("trade_dates")) {
                    return "trade_dates_with_offsets";
                }
                return "offsets";
            }
            return "trade_dates";
        }
        String mode = modeRaw.toString().trim().toLowerCase();
        return switch (mode) {
            case "trade_dates", "trade_date", "date", "dates" -> "trade_dates";
            case "offsets", "offset", "offset_range", "range" -> "offsets";
            case "trade_dates_with_offsets", "dates_with_offsets", "combined" -> "trade_dates_with_offsets";
            case "date_range_with_offsets", "date_range_offset", "fixed_date_range" -> "date_range_with_offsets";
            default -> throw new IllegalArgumentException("不支持的 task_set_mode: " + modeRaw);
        };
    }

    private void normalizeParamsInPlace(Map<String, Object> params) {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key.toLowerCase().contains("timestamp") && value != null) {
                Long ts = convertDateToTimestampMs(value);
                if (ts != null) {
                    entry.setValue(ts);
                }
            }
        }
    }

    private Long convertDateToTimestampMs(Object value) {
        if (value == null) {
            return null;
        }
        String raw = value.toString().trim();
        if (raw.matches("\\d{8}")) {
            LocalDate d = LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE);
            return d.atStartOfDay(ZONE_SHANGHAI).toInstant().toEpochMilli();
        }
        if (raw.matches("\\d+")) {
            return Long.parseLong(raw);
        }
        return null;
    }

    private String getTaskName(Map<String, Object> raw) {
        Object name = raw.get("task_name");
        if (name == null) {
            name = raw.get("task_type");
        }
        return name == null ? null : name.toString().trim();
    }

    private String buildDispatchPayload(String taskUuid, String taskName, int taskSubType, Map<String, Object> taskParams) {
        JSONObject payload = new JSONObject();
        payload.put("task_type", "fetch");
        payload.put("task_name", taskName);
        payload.put("task_sub_type", taskSubType);
        payload.put("task_params", new JSONObject(taskParams));
        if (taskUuid != null) {
            payload.put("task_uuid", taskUuid);
        }
        return payload.toJSONString();
    }

    private String buildParamsSummary(String taskName, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        sb.append(taskName);
        if (params != null && !params.isEmpty()) {
            sb.append(" | ");
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                parts.add(entry.getKey() + "=" + entry.getValue());
            }
            sb.append(String.join(", ", parts));
        }
        String summary = sb.toString();
        if (summary.length() > 500) {
            return summary.substring(0, 500);
        }
        return summary;
    }

    // ==================== 派发 ====================

    private void dispatchLeafTask(LeafTask leaf) {
        if ("trade_calendar".equals(leaf.taskName)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = leaf.taskParams;
            long startDateTimestamp = parseLongParam(params.get("start_date_timestamp"), 0);
            long endDateTimestamp = parseLongParam(params.get("end_date_timestamp"), 0);
            if (startDateTimestamp == 0 && params.containsKey("start_date")) {
                LocalDate d = parseDateValue(params.get("start_date"));
                if (d != null) startDateTimestamp = dateToTimestampMs(d);
            }
            if (endDateTimestamp == 0 && params.containsKey("end_date")) {
                LocalDate d = parseDateValue(params.get("end_date"));
                if (d != null) endDateTimestamp = dateToTimestampMs(d);
            }
            int offset = getIntValue(params.get("offset"), 0);
            int limit = getIntValue(params.get("limit"), DEFAULT_LIMIT);

            DomesticTradeCalendarFetchByDateRangeRequest request =
                    DomesticTradeCalendarFetchByDateRangeRequest.newBuilder()
                            .setStartDate(startDateTimestamp)
                            .setEndDate(endDateTimestamp)
                            .setOffset(offset)
                            .setLimit(limit)
                            .build();

            var future = domesticTradeCalendarFetchService.fetchDomesticTradeCalendarByDateRangeAsync(request);
            adminFetchTaskDao.markRunning(leaf.taskUuid, OffsetDateTime.now());

            future.whenComplete((response, ex) -> {
                if (ex != null) {
                    log.error("Admin fetch task failed taskUuid={} taskName=trade_calendar", leaf.taskUuid, ex);
                    markTaskFailure(leaf.taskUuid, -1, ex.getMessage());
                    return;
                }
                int fetched = response.getFetchedItemsCount();
                if ("success".equalsIgnoreCase(response.getStatus())) {
                    markTaskSuccess(leaf.taskUuid, fetched);
                } else {
                    markTaskFailure(leaf.taskUuid, fetched, response.getStatus());
                }
            });
        } else {
            if (!rateLimitingService.tryAcquire("task")) {
                markTaskFailure(leaf.taskUuid, -1, "Too many task creation requests");
                return;
            }
            fetchTaskStatusService.registerTask(leaf.taskUuid, leaf.taskName, leaf.taskSubType);
            try {
                rabbitTemplate.convertAndSend(
                        TaskProducerRabbitConfig.FETCH_EXCHANGE,
                        TaskProducerRabbitConfig.FETCH_TASK_ROUTING_KEY,
                        leaf.dispatchPayload
                );
                adminFetchTaskDao.markRunning(leaf.taskUuid, OffsetDateTime.now());
            } catch (Exception e) {
                log.error("Failed to dispatch leaf task to rabbitmq taskUuid={}", leaf.taskUuid, e);
                fetchTaskStatusService.markFailure(leaf.taskUuid, leaf.taskName, leaf.taskSubType, -1, e.getMessage());
                markTaskFailure(leaf.taskUuid, -1, e.getMessage());
            }
        }
    }

    private long parseLongParam(Object value, long defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int getIntValue(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString().trim();
    }

    // ==================== 状态更新（供内部 Dubbo 回调使用） ====================

    private void markTaskSuccess(String taskUuid, int fetchedItemsCount) {
        OffsetDateTime now = OffsetDateTime.now();
        adminFetchTaskDao.markSuccess(taskUuid, fetchedItemsCount, now, now);
        refreshJobCountersByTaskUuid(taskUuid);
    }

    private void markTaskFailure(String taskUuid, int fetchedItemsCount, String message) {
        OffsetDateTime now = OffsetDateTime.now();
        adminFetchTaskDao.markFailure(taskUuid, fetchedItemsCount, message, now, now);
        refreshJobCountersByTaskUuid(taskUuid);
    }

    private void refreshJobCountersByTaskUuid(String taskUuid) {
        AdminFetchTask task = adminFetchTaskDao.getByTaskUuid(taskUuid);
        if (task != null && task.getJobUuid() != null) {
            refreshJobCounters(task.getJobUuid());
        }
    }

    // ==================== 公开的状态刷新（供 Listener 调用） ====================

    public void refreshJobCounters(String jobUuid) {
        int pending = adminFetchTaskDao.countByJobUuidAndStatus(jobUuid, "PENDING");
        int running = adminFetchTaskDao.countByJobUuidAndStatus(jobUuid, "RUNNING");
        int success = adminFetchTaskDao.countByJobUuidAndStatus(jobUuid, "SUCCESS");
        int failure = adminFetchTaskDao.countByJobUuidAndStatus(jobUuid, "FAILURE");

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime finishedAt = null;
        if (pending == 0 && running == 0) {
            finishedAt = now;
        }
        adminFetchJobDao.updateCounters(jobUuid, pending, running, success, failure, now, finishedAt);
    }

    // ==================== 查询 ====================

    public Map<String, Object> listJobs(String status, String mode, String jobUuid,
                                        String createdFrom, String createdTo, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<AdminFetchJob> items = adminFetchJobDao.listByConditions(
                status, mode, jobUuid, createdFrom, createdTo, pageSize, offset);
        int total = adminFetchJobDao.countByConditions(status, mode, jobUuid, createdFrom, createdTo);

        FetchQueueService.FetchQueueStats queueStats = null;
        try {
            queueStats = fetchQueueService.getFetchQueueStats();
        } catch (Exception e) {
            log.warn("Failed to get fetch queue stats", e);
        }
        int runningJobs = adminFetchJobDao.countRunning();
        int runningTasks = adminFetchTaskDao.countRunning();

        OffsetDateTime todayStart = LocalDate.now(ZONE_SHANGHAI).atStartOfDay(ZONE_SHANGHAI).toOffsetDateTime();
        OffsetDateTime todayEnd = todayStart.plusDays(1);
        int successToday = adminFetchJobDao.countTodayByStatus("SUCCESS", todayStart, todayEnd)
                + adminFetchJobDao.countTodayByStatus("PARTIAL_SUCCESS", todayStart, todayEnd);
        int failureToday = adminFetchJobDao.countTodayByStatus("FAILURE", todayStart, todayEnd);

        Map<String, Object> summary = new HashMap<>();
        summary.put("queuePending", queueStats != null ? queueStats.pending() : 0);
        summary.put("queueConsumers", queueStats != null ? queueStats.consumers() : 0);
        summary.put("runningJobs", runningJobs);
        summary.put("runningTasks", runningTasks);
        summary.put("successToday", successToday);
        summary.put("failureToday", failureToday);

        Map<String, Object> result = new HashMap<>();
        result.put("items", items.stream().map(this::convertJobToMap).toList());
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("summary", summary);
        return result;
    }

    public Map<String, Object> getJobDetail(String jobUuid) {
        AdminFetchJob job = adminFetchJobDao.getByJobUuid(jobUuid);
        if (job == null) {
            return null;
        }
        Map<String, Object> map = convertJobToMap(job);
        map.put("requestedSpec", parseJson(job.getRequestedSpec()));
        map.put("normalizedSpec", parseJson(job.getNormalizedSpec()));

        int pending = adminFetchTaskDao.countByJobUuidAndStatus(jobUuid, "PENDING");
        int running = adminFetchTaskDao.countByJobUuidAndStatus(jobUuid, "RUNNING");
        int success = adminFetchTaskDao.countByJobUuidAndStatus(jobUuid, "SUCCESS");
        int failure = adminFetchTaskDao.countByJobUuidAndStatus(jobUuid, "FAILURE");
        Map<String, Object> expansionSummary = new LinkedHashMap<>();
        expansionSummary.put("pending", pending);
        expansionSummary.put("running", running);
        expansionSummary.put("success", success);
        expansionSummary.put("failure", failure);
        map.put("expansionSummary", expansionSummary);

        List<AdminFetchTask> previewTasks = adminFetchTaskDao.listByJobUuid(jobUuid, 20, 0);
        map.put("tasksPreview", previewTasks.stream().map(this::convertTaskToPreviewMap).toList());
        return map;
    }

    // ==================== 重试 ====================

    @Transactional
    public Map<String, Object> retryJobFailures(String jobUuid) {
        AdminFetchJob job = adminFetchJobDao.getByJobUuid(jobUuid);
        if (job == null) {
            throw new IllegalArgumentException("Job not found");
        }
        List<String> failureUuids = adminFetchTaskDao.listFailureTaskUuidsByJobUuid(jobUuid);
        if (failureUuids.isEmpty()) {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("jobUuid", jobUuid);
            res.put("retriedCount", 0);
            res.put("message", "No failed tasks to retry");
            return res;
        }

        List<Map<String, Object>> results = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        for (String sourceUuid : failureUuids) {
            AdminFetchTask sourceTask = adminFetchTaskDao.getByTaskUuid(sourceUuid);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("sourceTaskUuid", sourceUuid);
            if (sourceTask == null || sourceTask.getDispatchPayload() == null) {
                r.put("success", false);
                r.put("message", "source task or payload missing");
                results.add(r);
                continue;
            }
            String newTaskUuid = UUID.randomUUID().toString();
            AdminFetchTask newTask = new AdminFetchTask();
            newTask.setTaskUuid(newTaskUuid);
            newTask.setJobUuid(jobUuid);
            newTask.setTaskName(sourceTask.getTaskName());
            newTask.setTaskSubType(sourceTask.getTaskSubType());
            newTask.setStatus("PENDING");
            newTask.setSourceKind(sourceTask.getSourceKind());
            newTask.setSourceIndex(sourceTask.getSourceIndex());
            newTask.setTaskSetMode(sourceTask.getTaskSetMode());
            newTask.setParamsSummary(sourceTask.getParamsSummary());
            newTask.setInputParams(sourceTask.getInputParams());
            newTask.setDispatchPayload(sourceTask.getDispatchPayload());
            newTask.setCreatedBy(sourceTask.getCreatedBy());
            newTask.setCreatedAt(now);
            newTask.setUpdatedAt(now);
            newTask.setRetryOfTaskUuid(sourceUuid);
            adminFetchTaskDao.insert(newTask);

            LeafTask leaf = new LeafTask();
            leaf.taskUuid = newTaskUuid;
            leaf.taskName = sourceTask.getTaskName();
            leaf.taskSubType = sourceTask.getTaskSubType();
            leaf.dispatchPayload = sourceTask.getDispatchPayload();
            Map<String, Object> payloadMap = parseJsonToMap(sourceTask.getDispatchPayload());
            Object tp = payloadMap.get("task_params");
            if (tp instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) tp;
                leaf.taskParams = m;
            } else {
                leaf.taskParams = new LinkedHashMap<>();
            }
            dispatchLeafTask(leaf);

            r.put("newTaskUuid", newTaskUuid);
            r.put("success", true);
            results.add(r);
        }

        // 刷新计数
        refreshJobCounters(jobUuid);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("jobUuid", jobUuid);
        res.put("retriedCount", results.stream().filter(m -> Boolean.TRUE.equals(m.get("success"))).count());
        res.put("results", results);
        return res;
    }

    // ==================== 转换工具 ====================

    private Map<String, Object> convertJobToMap(AdminFetchJob job) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jobUuid", job.getJobUuid());
        map.put("label", job.getLabel());
        map.put("mode", job.getMode());
        map.put("status", job.getStatus());
        map.put("expandedTaskCount", job.getExpandedTaskCount());
        map.put("pendingCount", job.getPendingCount());
        map.put("runningCount", job.getRunningCount());
        map.put("successCount", job.getSuccessCount());
        map.put("failureCount", job.getFailureCount());
        map.put("createdBy", job.getCreatedBy());
        map.put("createdAt", formatDateTime(job.getCreatedAt()));
        map.put("updatedAt", formatDateTime(job.getUpdatedAt()));
        map.put("finishedAt", formatDateTime(job.getFinishedAt()));
        return map;
    }

    private Map<String, Object> convertTaskToPreviewMap(AdminFetchTask task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskUuid", task.getTaskUuid());
        map.put("taskName", task.getTaskName());
        map.put("taskSubType", task.getTaskSubType());
        map.put("status", task.getStatus());
        map.put("sourceKind", task.getSourceKind());
        map.put("sourceIndex", task.getSourceIndex());
        map.put("taskSetMode", task.getTaskSetMode());
        map.put("createdAt", formatDateTime(task.getCreatedAt()));
        return map;
    }

    private String formatDateTime(OffsetDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.toString();
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            return json;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonToMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Object obj = JSON.parseObject(json);
            if (obj instanceof Map) {
                return (Map<String, Object>) obj;
            }
        } catch (Exception e) {
            log.warn("Failed to parse json to map: {}", json);
        }
        return new LinkedHashMap<>();
    }

    // ==================== 内部类 ====================

    private static class LeafTask {
        String taskUuid;
        String taskName;
        int taskSubType;
        Map<String, Object> taskParams;
        String sourceKind;
        int sourceIndex;
        String taskSetMode;
        String paramsSummary;
        String inputParams;
        String dispatchPayload;
    }
}

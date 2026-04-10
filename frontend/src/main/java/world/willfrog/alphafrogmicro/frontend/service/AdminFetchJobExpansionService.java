package world.willfrog.alphafrogmicro.frontend.service;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.agent.AdminFetchTaskDao;
import world.willfrog.alphafrogmicro.common.pojo.agent.AdminFetchTask;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradeCalendarFetchByDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradeCalendarFetchService;
import world.willfrog.alphafrogmicro.frontend.config.TaskProducerRabbitConfig;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/**
 * Admin Fetch Job 任务展开与异步派发服务
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AdminFetchJobExpansionService {

    private final AdminFetchTaskDao adminFetchTaskDao;
    private final AdminFetchJobCounterService counterService;
    private final RabbitTemplate rabbitTemplate;
    private final FetchTaskStatusService fetchTaskStatusService;
    private final RateLimitingService rateLimitingService;
    private final Executor fetchJobDispatchExecutor;

    @DubboReference
    private DomesticTradeCalendarFetchService domesticTradeCalendarFetchService;

    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
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

    // 以下任务因 TuShare 接口限制，需先按 offset/limit 从本地库取 ts_code，再逐个请求。
    // 此时 offset_range 控制的是 TuShare 层分页（api_offset/api_limit），
    // 而 task_params.limit/offset 控制的是本地指数库分批（indexLimit/indexOffset）。
    private static final Set<String> TUSHARE_SECONDARY_PAGING_TASKS = Set.of("index_quote", "index_weight");

    // ==================== 展开逻辑 ====================

    public List<LeafTask> expandJobBody(Map<String, Object> body, String mode) {
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

        return leafTasks;
    }

    private List<LeafTask> expandRawTasks(List<Map<String, Object>> tasks, String sourceKind) {
        List<LeafTask> result = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            Map<String, Object> raw = tasks.get(i);
            LeafTask leaf = normalizeSingleTask(raw, i);
            leaf.sourceKind = sourceKind;
            leaf.sourceIndex = i;
            leaf.sourceScope = "tasks[" + i + "]";
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
            List<LeafTask> leaves = expandSingleTaskSet(taskSets.get(i), i);
            for (LeafTask leaf : leaves) {
                leaf.sourceScope = "task_sets[" + i + "]";
            }
            result.addAll(leaves);
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
        Set<String> skipKeys = Set.of("task_name", "task_type", "task_sub_type", "task_subtype", "taskSubType",
                "task_params", "trade_dates", "date_range", "task_set_mode", "expand_mode",
                "offset_range", "offset_start", "offset_end", "offset_step", "ingest_token", "execution_options");
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
                    if (TUSHARE_SECONDARY_PAGING_TASKS.contains(taskName)) {
                        p.put("api_offset", off);
                        if (!p.containsKey("api_limit")) {
                            int step = computeStep(rawTask);
                            p.put("api_limit", step > 0 ? step : off + 1);
                        }
                    } else {
                        p.put("offset", off);
                    }
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
                    if (TUSHARE_SECONDARY_PAGING_TASKS.contains(taskName)) {
                        p.put("api_offset", off);
                        if (!p.containsKey("api_limit")) {
                            int step = computeStep(rawTask);
                            p.put("api_limit", step > 0 ? step : off + 1);
                        }
                    } else {
                        p.put("offset", off);
                        if (!p.containsKey("limit")) {
                            p.put("limit", 3000);
                        }
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
                        if (TUSHARE_SECONDARY_PAGING_TASKS.contains(taskName)) {
                            p.put("api_offset", off);
                            if (!p.containsKey("api_limit")) {
                                int step = computeStep(rawTask);
                                p.put("api_limit", step > 0 ? step : off + 1);
                            }
                        } else {
                            p.put("offset", off);
                        }
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
            leaf.sourceScope = "fetch_info." + infoType;
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

        Set<String> skipKeys = Set.of("task_name", "task_type", "task_sub_type", "task_subtype", "taskSubType", "task_params", "ingest_token", "execution_options");
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

    private int computeStep(Map<String, Object> rawTask) {
        @SuppressWarnings("unchecked")
        Map<String, Object> offsetRange = rawTask.get("offset_range") == null ? null : (Map<String, Object>) rawTask.get("offset_range");
        Object step = offsetRange != null ? offsetRange.get("step") : rawTask.get("offset_step");
        if (step == null && offsetRange != null) {
            step = offsetRange.get("offset_step");
        }
        if (step == null) {
            return 0;
        }
        try {
            if (step instanceof Number n) return n.intValue();
            return Integer.parseInt(step.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
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

    public LocalDate parseDateValue(Object value) {
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

    public long dateToTimestampMs(LocalDate date) {
        return date.atStartOfDay(ZONE_SHANGHAI).toInstant().toEpochMilli();
    }

    public String dateToString(LocalDate date) {
        return date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    public int parseIntValue(Object value, String fieldName, int index) {
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

    public String normalizeTaskSetMode(Map<String, Object> rawTask) {
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

    public Long convertDateToTimestampMs(Object value) {
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

    public String getTaskName(Map<String, Object> raw) {
        Object name = raw.get("task_name");
        if (name == null) {
            name = raw.get("task_type");
        }
        return name == null ? null : name.toString().trim();
    }

    public String buildDispatchPayload(String taskUuid, String taskName, int taskSubType, Map<String, Object> taskParams) {
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

    public String buildParamsSummary(String taskName, Map<String, Object> params) {
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

    public int getIntValue(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString().trim();
    }

    // ==================== 异步派发 ====================

    @Async("fetchJobDispatchExecutor")
    public void dispatchJobAsync(String jobUuid, int workerThreads, int taskIntervalMs) {
        int pageSize = 500;
        int offset = 0;
        Semaphore semaphore = new Semaphore(Math.max(1, workerThreads));

        while (true) {
            List<AdminFetchTask> pendingTasks = adminFetchTaskDao.listPendingByJobUuid(jobUuid, pageSize, offset);
            if (pendingTasks.isEmpty()) {
                break;
            }
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (AdminFetchTask task : pendingTasks) {
                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (taskIntervalMs > 0) {
                    try {
                        Thread.sleep(taskIntervalMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        semaphore.release();
                        break;
                    }
                }
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        LeafTask leaf = convertTaskToLeaf(task);
                        dispatchLeafTask(leaf);
                    } finally {
                        semaphore.release();
                    }
                }, fetchJobDispatchExecutor));
            }
            if (!futures.isEmpty()) {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }
            offset += pageSize;
        }
    }

    private LeafTask convertTaskToLeaf(AdminFetchTask task) {
        LeafTask leaf = new LeafTask();
        leaf.taskUuid = task.getTaskUuid();
        leaf.taskName = task.getTaskName();
        leaf.taskSubType = task.getTaskSubType();
        leaf.sourceKind = task.getSourceKind();
        leaf.sourceIndex = task.getSourceIndex();
        leaf.taskSetMode = task.getTaskSetMode();
        leaf.dispatchPayload = task.getDispatchPayload();
        Map<String, Object> payloadMap = parseJsonToMap(task.getDispatchPayload());
        Object tp = payloadMap.get("task_params");
        if (tp instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = new LinkedHashMap<>((Map<String, Object>) m);
            leaf.taskParams = params;
        } else {
            leaf.taskParams = new LinkedHashMap<>();
        }
        return leaf;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonToMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Object obj = com.alibaba.fastjson2.JSON.parseObject(json);
            if (obj instanceof Map) {
                return (Map<String, Object>) obj;
            }
        } catch (Exception e) {
            log.warn("Failed to parse json to map: {}", json);
        }
        return new LinkedHashMap<>();
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

    private void markTaskSuccess(String taskUuid, int fetchedItemsCount) {
        OffsetDateTime now = OffsetDateTime.now();
        adminFetchTaskDao.markSuccess(taskUuid, fetchedItemsCount, now, now);
        counterService.refreshJobCountersByTaskUuid(taskUuid);
    }

    private void markTaskFailure(String taskUuid, int fetchedItemsCount, String message) {
        OffsetDateTime now = OffsetDateTime.now();
        adminFetchTaskDao.markFailure(taskUuid, fetchedItemsCount, message, now, now);
        counterService.refreshJobCountersByTaskUuid(taskUuid);
    }

    // ==================== 内部类 ====================

    public static class LeafTask {
        public String taskUuid;
        public String taskName;
        public int taskSubType;
        public Map<String, Object> taskParams;
        public String sourceKind;
        public int sourceIndex;
        public String sourceScope;
        public String taskSetMode;
        public String paramsSummary;
        public String inputParams;
        public String dispatchPayload;
    }
}

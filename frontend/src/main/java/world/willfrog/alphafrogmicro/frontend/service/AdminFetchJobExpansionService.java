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
import world.willfrog.alphafrogmicro.common.fetchcatalog.FetchCatalogConfigLoader;
import world.willfrog.alphafrogmicro.common.fetchcatalog.TaskSetVariantConfig;
import world.willfrog.alphafrogmicro.common.fetchcatalog.TaskVariantConfig;

import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/**
 * Admin Fetch Job 任务展开与异步派发服务。
 * 负责把前端提交的 tasks / task_sets / fetch_info 展开成扁平的 LeafTask，
 * 并在创建 Job 后通过 RabbitMQ 异步派发。
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
    private final FetchCatalogConfigLoader catalogLoader;

    @DubboReference
    private DomesticTradeCalendarFetchService domesticTradeCalendarFetchService;

    private static final int DEFAULT_LIMIT = 5000;
    /** 单次展开的叶子任务数量上限，防止内存溢出 */
    static final int MAX_EXPAND_LEAF_TASKS = 5000;

    @PostConstruct
    public void init() {
        AdminFetchJobMeta.refresh(catalogLoader);
    }

    // fetch_info 里的类型到任务名的映射
    private static final Map<String, String> INFO_TASK_NAME = Map.of(
            "fund", "fund_info",
            "stock", "stock_info",
            "index", "index_info"
    );

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

    /**
     * 核心展开逻辑：基于 JSON 配置的 expandStrategy 做通用展开。
     */
    private List<LeafTask> expandSingleTaskSet(Map<String, Object> rawTask, int index) {
        String taskName = getTaskName(rawTask);
        if (taskName == null || taskName.isBlank()) {
            throw new IllegalArgumentException("task_sets[" + index + "] 缺少 task_name");
        }

        int taskSetSubType = getIntValue(rawTask.get("task_set_sub_type"), 1);
        // 兼容旧协议：如果传的是 task_sub_type 且没有 task_set_sub_type
        if (rawTask.get("task_set_sub_type") == null && rawTask.get("task_sub_type") != null) {
            taskSetSubType = getIntValue(rawTask.get("task_sub_type"), 1);
        }

        TaskSetVariantConfig config = catalogLoader.findTaskSetVariant(taskName, taskSetSubType);
        if (config == null) {
            throw new IllegalArgumentException("task_sets[" + index + "] 不支持的任务类型或 task_set_sub_type: " + taskName + "/" + taskSetSubType);
        }

        String expandStrategy = config.getExpandStrategy();
        int outputTaskSubType = config.getOutputTaskVariantSubType();

        Map<String, Object> baseParams = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> taskParams = (Map<String, Object>) rawTask.get("task_params");
        if (taskParams != null) {
            baseParams.putAll(taskParams);
        }
        Set<String> skipKeys = Set.of("task_name", "task_type", "task_sub_type", "task_subtype", "taskSubType",
                "task_set_sub_type", "task_params", "trade_dates", "date_range",
                "task_set_mode", "expand_mode", "offset_range", "offset_start", "offset_end", "offset_step",
                "ingest_token", "execution_options");
        for (Map.Entry<String, Object> entry : rawTask.entrySet()) {
            if (skipKeys.contains(entry.getKey())) continue;
            if (!baseParams.containsKey(entry.getKey())) {
                baseParams.put(entry.getKey(), entry.getValue());
            }
        }

        // 处理双层分页参数：如果 JSON 配置了 api_offset_start/end/step，从 offset_range 或顶层读取
        if (config.hasFlag("secondary_paging")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> offsetRange = rawTask.get("offset_range") == null ? null : (Map<String, Object>) rawTask.get("offset_range");
            int apiStart = 0, apiEnd = 0, apiStep = 0;
            if (offsetRange != null) {
                Object startObj = offsetRange.get("start");
                if (startObj == null) startObj = offsetRange.get("offset_start");
                Object endObj = offsetRange.get("end");
                if (endObj == null) endObj = offsetRange.get("offset_end");
                Object stepObj = offsetRange.get("step");
                if (stepObj == null) stepObj = offsetRange.get("offset_step");
                apiStart = startObj != null ? getIntValue(startObj, 0) : 0;
                apiEnd = endObj != null ? getIntValue(endObj, 0) : 0;
                apiStep = stepObj != null ? getIntValue(stepObj, 0) : 0;
            }
            if (!baseParams.containsKey("api_offset_start")) baseParams.put("api_offset_start", apiStart);
            if (!baseParams.containsKey("api_offset_end")) baseParams.put("api_offset_end", apiEnd);
            if (!baseParams.containsKey("api_offset_step")) baseParams.put("api_offset_step", apiStep);
        }

        List<Map<String, Object>> expandedParamsList = new ArrayList<>();

        switch (expandStrategy) {
            case "trade_dates" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> tradeDates = (Map<String, Object>) rawTask.get("trade_dates");
                List<LocalDate> dates = expandTradeDates(tradeDates, index);
                for (LocalDate d : dates) {
                    Map<String, Object> p = new LinkedHashMap<>(baseParams);
                    String dateStr = dateToString(d);
                    p.put("trade_date", dateStr);
                    // 同时设置 start_date 和 end_date，供按日期范围抓取的任务（如 fund_portfolio）使用
                    p.put("start_date", dateStr);
                    p.put("end_date", dateStr);
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
            }
            case "trade_dates_with_offsets" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> tradeDates = (Map<String, Object>) rawTask.get("trade_dates");
                if (tradeDates == null) {
                    throw new IllegalArgumentException("task_sets[" + index + "] trade_dates_with_offsets 模式需要 trade_dates 配置");
                }
                List<LocalDate> dates = expandTradeDates(tradeDates, index);
                List<Integer> offsets = expandOffsets(rawTask, index);
                // 笛卡尔积预估检查
                long estimated = (long) dates.size() * offsets.size();
                if (estimated > MAX_EXPAND_LEAF_TASKS) {
                    throw new IllegalArgumentException("task_sets[" + index + "] 笛卡尔积预估任务数 " + estimated + " 超过上限 " + MAX_EXPAND_LEAF_TASKS);
                }
                for (LocalDate d : dates) {
                    for (int off : offsets) {
                        Map<String, Object> p = new LinkedHashMap<>(baseParams);
                        p.put("trade_date", dateToString(d));
                        p.put("offset", off);
                        expandedParamsList.add(p);
                    }
                }
            }
            case "index_batches" -> {
                // index_batches: 按本地指数分批展开，使用 index_offset/index_batch_limit 区分于 TuShare 分页的 offset/limit
                int baseOffset = getIntValue(baseParams.get("index_offset"), getIntValue(baseParams.get("offset"), 0));
                int batchSize = readIndexBatchSize(baseParams);
                int indexCountLimit = getIntValue(baseParams.get("index_count_limit"), batchSize);
                if (indexCountLimit <= 0) indexCountLimit = batchSize;
                int batchCount = (indexCountLimit + batchSize - 1) / batchSize;
                for (int b = 0; b < batchCount; b++) {
                    Map<String, Object> p = new LinkedHashMap<>(baseParams);
                    p.put("index_offset", baseOffset + b * batchSize);
                    p.put("index_batch_limit", batchSize);
                    expandedParamsList.add(p);
                }
            }
            case "trade_dates_with_index_batches" -> {
                // trade_dates_with_index_batches: 交易日 x 本地指数批次笛卡尔积展开
                @SuppressWarnings("unchecked")
                Map<String, Object> tradeDates = (Map<String, Object>) rawTask.get("trade_dates");
                List<LocalDate> dates = expandTradeDates(tradeDates, index);
                int baseOffset = getIntValue(baseParams.get("index_offset"), getIntValue(baseParams.get("offset"), 0));
                int batchSize = readIndexBatchSize(baseParams);
                int indexCountLimit = getIntValue(baseParams.get("index_count_limit"), batchSize);
                if (indexCountLimit <= 0) indexCountLimit = batchSize;
                int batchCount = (indexCountLimit + batchSize - 1) / batchSize;
                for (LocalDate d : dates) {
                    for (int b = 0; b < batchCount; b++) {
                        Map<String, Object> p = new LinkedHashMap<>(baseParams);
                        p.put("trade_date", dateToString(d));
                        p.put("index_offset", baseOffset + b * batchSize);
                        p.put("index_batch_limit", batchSize);
                        expandedParamsList.add(p);
                    }
                }
            }
            case "date_range_with_index_batches" -> {
                // date_range_with_index_batches: 固定日期范围 x 本地指数批次笛卡尔积展开
                @SuppressWarnings("unchecked")
                Map<String, Object> dateRange = (Map<String, Object>) rawTask.get("date_range");
                if (dateRange == null) {
                    throw new IllegalArgumentException("task_sets[" + index + "] date_range_with_index_batches 模式需要 date_range 配置");
                }
                Object startDateRaw = dateRange.get("start_date");
                Object endDateRaw = dateRange.get("end_date");
                if (startDateRaw == null || endDateRaw == null) {
                    throw new IllegalArgumentException("task_sets[" + index + "] date_range_with_index_batches 模式需要 start_date 和 end_date");
                }
                int baseOffset = getIntValue(baseParams.get("index_offset"), getIntValue(baseParams.get("offset"), 0));
                int batchSize = readIndexBatchSize(baseParams);
                int indexCountLimit = getIntValue(baseParams.get("index_count_limit"), batchSize);
                if (indexCountLimit <= 0) indexCountLimit = batchSize;
                int batchCount = (indexCountLimit + batchSize - 1) / batchSize;
                for (int b = 0; b < batchCount; b++) {
                    Map<String, Object> p = new LinkedHashMap<>(baseParams);
                    p.put("start_date", String.valueOf(startDateRaw));
                    p.put("end_date", String.valueOf(endDateRaw));
                    p.put("index_offset", baseOffset + b * batchSize);
                    p.put("index_batch_limit", batchSize);
                    expandedParamsList.add(p);
                }
            }
            case "date_range_with_api_offsets" -> {
                // date_range_with_api_offsets: 固定日期范围 + 按 TuShare offset/limit 直接分页展开
                @SuppressWarnings("unchecked")
                Map<String, Object> dateRange = (Map<String, Object>) rawTask.get("date_range");
                if (dateRange == null) {
                    throw new IllegalArgumentException("task_sets[" + index + "] date_range_with_api_offsets 模式需要 date_range 配置");
                }
                Object startDateRaw = dateRange.get("start_date");
                Object endDateRaw = dateRange.get("end_date");
                if (startDateRaw == null || endDateRaw == null) {
                    throw new IllegalArgumentException("task_sets[" + index + "] date_range_with_api_offsets 模式需要 start_date 和 end_date");
                }
                int baseOffset = getIntValue(baseParams.get("offset"), 0);
                int step = getIntValue(baseParams.get("limit"), 2000);
                int taskCount = getIntValue(baseParams.get("task_count"), 1);
                if (step <= 0) step = 2000;
                if (taskCount <= 0) taskCount = 1;
                for (int i = 0; i < taskCount; i++) {
                    Map<String, Object> p = new LinkedHashMap<>(baseParams);
                    p.put("start_date", String.valueOf(startDateRaw));
                    p.put("end_date", String.valueOf(endDateRaw));
                    p.put("offset", baseOffset + i * step);
                    p.put("limit", step);
                    expandedParamsList.add(p);
                }
            }
            default -> throw new IllegalArgumentException("不支持的 expandStrategy: " + expandStrategy);
        }

        // 早期中止：如果展开结果超出上限，立即抛出异常避免 OOM
        if (expandedParamsList.size() > MAX_EXPAND_LEAF_TASKS) {
            throw new IllegalArgumentException(
                    "task_sets[" + index + "] 展开后任务数 " + expandedParamsList.size()
                            + " 超过上限 " + MAX_EXPAND_LEAF_TASKS);
        }

        List<LeafTask> result = new ArrayList<>();
        for (int i = 0; i < expandedParamsList.size(); i++) {
            Map<String, Object> p = expandedParamsList.get(i);
            LeafTask leaf = new LeafTask();
            leaf.taskName = taskName;
            leaf.taskSubType = outputTaskSubType;
            leaf.taskParams = p;
            leaf.sourceKind = "TASK_SET";
            leaf.sourceIndex = index;
            leaf.taskSetMode = expandStrategy;
            leaf.paramsSummary = buildParamsSummary(taskName, p);
            leaf.inputParams = JSONObject.toJSONString(rawTask);
            leaf.dispatchPayload = buildDispatchPayload(leaf.taskUuid, taskName, outputTaskSubType, p);
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

        TaskVariantConfig config = catalogLoader.findTaskVariant(taskName, taskSubType);
        if (config == null) {
            throw new IllegalArgumentException("tasks[" + index + "] 未知的任务类型或子类型: " + taskName + "/" + taskSubType);
        }
        if (config.hasFlag("empty_params_allowed")) {
            // 允许空参数
        } else if (taskParams.isEmpty()) {
            throw new IllegalArgumentException("tasks[" + index + "] task_params 不能为空");
        }

        // 校验必填参数
        if (config.getRequiredParams() != null) {
            for (String req : config.getRequiredParams()) {
                if (isEmptyValue(taskParams.get(req))) {
                    throw new IllegalArgumentException("tasks[" + index + "] 缺少必填参数: " + req);
                }
            }
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
        String startStr = tradeDates.get("start_date") != null ? tradeDates.get("start_date").toString().trim() : null;
        String endStr = tradeDates.get("end_date") != null ? tradeDates.get("end_date").toString().trim() : null;
        if (startStr == null || startStr.isBlank() || endStr == null || endStr.isBlank()) {
            throw new IllegalArgumentException("task_sets[" + index + "] trade_dates 缺少 start_date 或 end_date");
        }
        LocalDate start = parseDateValue(startStr);
        LocalDate end = parseDateValue(endStr);
        if (start == null || end == null) {
            throw new IllegalArgumentException("task_sets[" + index + "] trade_dates 日期格式不正确，应为 yyyyMMdd");
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

    public LocalDate parseDateValue(Object value) {
        if (value == null) return null;
        String raw = value.toString().trim();
        try {
            return LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (Exception e) {
            return null;
        }
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
        return modeRaw.toString().trim().toLowerCase();
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

    /**
     * task_sets 中与「本地指数每批条数」对应的参数：{@code index_batch_limit}；
     * 兼容旧字段 {@code index_limit} 以及与 TuShare 分页同名的 {@code limit}（旧 YAML）。
     */
    private int readIndexBatchSize(Map<String, Object> baseParams) {
        return getIntValue(baseParams.get("index_batch_limit"),
                getIntValue(baseParams.get("index_limit"),
                        getIntValue(baseParams.get("limit"), 5000)));
    }

    public String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString().trim();
    }

    private boolean isEmptyValue(Object value) {
        if (value == null) return true;
        if (value instanceof String s) return s.isBlank();
        return false;
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
            long startDateTimestamp = 0;
            long endDateTimestamp = 0;
            String startDate = params.get("start_date") != null ? params.get("start_date").toString() : null;
            String endDate = params.get("end_date") != null ? params.get("end_date").toString() : null;
            if (startDate != null && !startDate.isBlank()) {
                LocalDate d = parseDateValue(startDate);
                if (d != null) {
                    startDateTimestamp = d.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
                }
            }
            if (endDate != null && !endDate.isBlank()) {
                LocalDate d = parseDateValue(endDate);
                if (d != null) {
                    endDateTimestamp = d.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
                }
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

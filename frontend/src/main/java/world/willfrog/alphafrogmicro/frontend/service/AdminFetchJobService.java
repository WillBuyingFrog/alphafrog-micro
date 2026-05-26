package world.willfrog.alphafrogmicro.frontend.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.alphafrogmicro.common.dao.agent.AdminFetchJobDao;
import world.willfrog.alphafrogmicro.common.dao.agent.AdminFetchTaskDao;
import world.willfrog.alphafrogmicro.common.pojo.agent.AdminFetchJob;
import world.willfrog.alphafrogmicro.frontend.service.AdminFetchJobExpansionService.LeafTask;
import world.willfrog.alphafrogmicro.frontend.service.AdminFetchJobMeta.TaskVariantMeta;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
    private final AdminFetchJobExpansionService expansionService;
    private final AdminFetchJobPreviewService previewService;
    private final AdminFetchJobCounterService counterService;
    private final FetchQueueService fetchQueueService;

    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int MAX_LEAF_TASKS = 5000;
    private static final int DEFAULT_WORKER_THREADS = 4;
    private static final int DEFAULT_TASK_INTERVAL_MS = 200;
    private static final Set<String> VALID_MODES = Set.of("tasks", "task_sets", "fetch_info", "all");

    // ==================== Catalog ====================

    public Map<String, Object> buildCatalog() {
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("taskCatalog", buildTaskCatalog());
        catalog.put("taskSetCatalog", buildTaskSetCatalog());
        catalog.put("fetchInfoCatalog", buildFetchInfoCatalog());
        catalog.put("quickPresets", buildQuickPresets());
        return catalog;
    }

    private List<Map<String, Object>> buildTaskCatalog() {
        return buildCatalogFromMetas(AdminFetchJobMeta.getVariantMetas(), "taskSubType");
    }

    private List<Map<String, Object>> buildTaskSetCatalog() {
        return buildCatalogFromMetas(AdminFetchJobMeta.getTaskSetVariantMetas(), "taskSetSubType");
    }

    private List<Map<String, Object>> buildCatalogFromMetas(List<TaskVariantMeta> metas, String subTypeKey) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, List<TaskVariantMeta>> grouped = new LinkedHashMap<>();
        for (TaskVariantMeta vm : metas) {
            grouped.computeIfAbsent(vm.taskName(), k -> new ArrayList<>()).add(vm);
        }
        for (Map.Entry<String, List<TaskVariantMeta>> entry : grouped.entrySet()) {
            Map<String, Object> taskMap = new LinkedHashMap<>();
            taskMap.put("taskName", entry.getKey());
            taskMap.put("label", AdminFetchJobMeta.taskLabel(entry.getKey()));
            List<Map<String, Object>> variants = new ArrayList<>();
            for (TaskVariantMeta vm : entry.getValue()) {
                variants.add(convertVariantMetaToMap(vm, subTypeKey));
            }
            taskMap.put("variants", variants);
            result.add(taskMap);
        }
        return result;
    }

    private Map<String, Object> convertVariantMetaToMap(TaskVariantMeta vm, String subTypeKey) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(subTypeKey, vm.taskSubType());
        map.put("label", vm.label());
        map.put("description", vm.description());
        map.put("allowedTaskSetModes", vm.allowedTaskSetModes());
        List<Map<String, Object>> fieldMaps = new ArrayList<>();
        for (AdminFetchJobMeta.FieldMeta f : vm.fields()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("name", f.name());
            fm.put("label", f.label());
            fm.put("inputType", f.inputType());
            fm.put("defaultValue", f.defaultValue());
            fm.put("description", f.description());
            fm.put("required", f.required());
            fm.put("effectiveWhen", convertConditionToMap(f.effectiveWhen()));
            fm.put("requiredWhen", convertConditionToMap(f.requiredWhen()));
            fm.put("ignoredWhen", convertConditionToMap(f.ignoredWhen()));
            if (f.validation() != null) {
                Map<String, Object> vmMap = new LinkedHashMap<>();
                vmMap.put("min", f.validation().min());
                vmMap.put("max", f.validation().max());
                vmMap.put("nonZero", f.validation().nonZero());
                vmMap.put("pattern", f.validation().pattern());
                fm.put("validation", vmMap);
            }
            fieldMaps.add(fm);
        }
        map.put("fields", fieldMaps);
        map.put("executionSummary", vm.executionSummaryTemplate());
        return map;
    }

    private Object convertConditionToMap(AdminFetchJobMeta.RuleCondition condition) {
        if (condition instanceof AdminFetchJobMeta.RuleCondition.Always) {
            return "ALWAYS";
        }
        if (condition instanceof AdminFetchJobMeta.RuleCondition.Never) {
            return "NEVER";
        }
        if (condition instanceof AdminFetchJobMeta.RuleCondition.PathIn pathIn) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("operator", "in");
            m.put("path", pathIn.path());
            m.put("values", pathIn.values());
            return m;
        }
        if (condition instanceof AdminFetchJobMeta.RuleCondition.PathEquals pathEquals) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("operator", "equals");
            m.put("path", pathEquals.path());
            m.put("value", pathEquals.value());
            return m;
        }
        if (condition instanceof AdminFetchJobMeta.RuleCondition.And and) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("operator", "and");
            m.put("conditions", and.conditions().stream().map(this::convertConditionToMap).toList());
            return m;
        }
        if (condition instanceof AdminFetchJobMeta.RuleCondition.Or or) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("operator", "or");
            m.put("conditions", or.conditions().stream().map(this::convertConditionToMap).toList());
            return m;
        }
        return null;
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
                List.of(Map.of("task_name", "stock_quote", "task_set_sub_type", 4, "task_set_mode", "date_range_with_offsets",
                        "task_params", Map.of("limit", 6000), "date_range", Map.of("start_date", "", "end_date", ""), "offset_range", Map.of("start", 0, "end", 10000, "step", 6000)))));
        list.add(preset("index_quote_trade_date", "指数行情单日", "tasks",
                List.of(Map.of("task_name", "index_quote", "task_sub_type", 1, "task_params", Map.of("trade_date", "", "index_offset", 0, "index_batch_limit", 5000)))));
        list.add(preset("index_quote_range", "指数行情范围", "task_sets",
                List.of(Map.of("task_name", "index_quote", "task_set_sub_type", 3, "task_set_mode", "date_range_with_index_batches",
                        "task_params", Map.of("index_offset", 0, "index_batch_limit", 5000, "index_count_limit", 5000), "date_range", Map.of("start_date", "", "end_date", "")))));
        list.add(preset("index_weight_range", "指数权重范围", "task_sets",
                List.of(Map.of("task_name", "index_weight", "task_set_sub_type", 2, "task_set_mode", "date_range_with_index_batches",
                        "task_params", Map.of("index_offset", 0, "index_batch_limit", 5000, "index_count_limit", 5000), "date_range", Map.of("start_date", "", "end_date", "")))));
        list.add(preset("index_weight_range_direct", "指数权重范围（直接分页）", "task_sets",
                List.of(Map.of("task_name", "index_weight", "task_set_sub_type", 3, "task_set_mode", "date_range_with_api_offsets",
                        "task_params", Map.of("offset", 0, "limit", 2000, "task_count", 5), "date_range", Map.of("start_date", "", "end_date", "")))));
        list.add(preset("fund_portfolio_range", "基金持仓范围", "task_sets",
                List.of(Map.of("task_name", "fund_portfolio", "task_set_sub_type", 1, "task_set_mode", "trade_dates",
                        "task_params", Map.of("offset", 0, "limit", 5000), "trade_dates", Map.of("start_date", "", "end_date", "")))));
        list.add(preset("trade_calendar_range", "交易日历范围", "task_sets",
                List.of(Map.of("task_name", "trade_calendar", "task_set_sub_type", 1, "task_set_mode", "date_range_with_offsets",
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

    // ==================== Preview ====================

    public Map<String, Object> previewJob(Map<String, Object> body) {
        return previewService.previewJob(body);
    }

    // ==================== Job 创建 ====================

    @Transactional
    public Map<String, Object> createJob(Map<String, Object> body, String createdBy) {
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }

        String mode = expansionService.getString(body, "mode");
        if (mode == null || !VALID_MODES.contains(mode)) {
            throw new IllegalArgumentException("mode 必须是 tasks / task_sets / fetch_info / all 之一");
        }

        String label = expansionService.getString(body, "label");
        if (label == null) {
            label = "";
        }

        List<LeafTask> leafTasks = expansionService.expandJobBody(body, mode);

        if (leafTasks.isEmpty()) {
            throw new IllegalArgumentException("没有可执行的任务，请检查请求体中的 tasks / task_sets / fetch_info 配置");
        }

        if (leafTasks.size() > MAX_LEAF_TASKS) {
            throw new IllegalArgumentException("展开后的叶子任务数超过上限 " + MAX_LEAF_TASKS + "，当前 " + leafTasks.size());
        }

        Map<String, Object> executionOptions = parseExecutionOptions(body);
        int workerThreads = (Integer) executionOptions.get("workerThreads");
        int taskIntervalMs = (Integer) executionOptions.get("taskIntervalMs");

        String jobUuid = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();

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
        job.setExecutionOptions(JSONObject.toJSONString(executionOptions));
        adminFetchJobDao.insert(job);

        List<Map<String, Object>> preview = new ArrayList<>();
        for (LeafTask leaf : leafTasks) {
            String taskUuid = UUID.randomUUID().toString();
            leaf.taskUuid = taskUuid;
            leaf.dispatchPayload = expansionService.buildDispatchPayload(taskUuid, leaf.taskName, leaf.taskSubType, leaf.taskParams);

            var task = new world.willfrog.alphafrogmicro.common.pojo.agent.AdminFetchTask();
            task.setTaskUuid(taskUuid);
            task.setJobUuid(jobUuid);
            task.setTemplateKey(leaf.taskName);
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

            Map<String, Object> p = new LinkedHashMap<>();
            p.put("taskUuid", taskUuid);
            p.put("taskName", leaf.taskName);
            p.put("taskSubType", leaf.taskSubType);
            p.put("status", "PENDING");
            preview.add(p);
        }

        expansionService.dispatchJobAsync(jobUuid, workerThreads, taskIntervalMs);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job", convertJobToMap(job));
        result.put("itemsPreview", preview);
        result.put("executionOptions", executionOptions);
        result.put("orchestrationMode", "SERVER_ASYNC");
        result.put("message", "Job creation request received and is being processed.");
        return result;
    }

    private Map<String, Object> parseExecutionOptions(Map<String, Object> body) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("workerThreads", DEFAULT_WORKER_THREADS);
        defaults.put("taskIntervalMs", DEFAULT_TASK_INTERVAL_MS);

        Object eoRaw = body.get("execution_options");
        if (!(eoRaw instanceof Map<?, ?> eoMap)) {
            return defaults;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> eo = (Map<String, Object>) eoMap;

        Object wt = eo.get("worker_threads");
        if (wt != null) {
            int w = expansionService.getIntValue(wt, DEFAULT_WORKER_THREADS);
            if (w >= 1 && w <= 20) {
                defaults.put("workerThreads", w);
            }
        }
        Object ti = eo.get("task_interval_ms");
        if (ti != null) {
            int t = expansionService.getIntValue(ti, DEFAULT_TASK_INTERVAL_MS);
            if (t >= 0 && t <= 5000) {
                defaults.put("taskIntervalMs", t);
            }
        }
        return defaults;
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

    // ==================== 查询 ====================

    public Map<String, Object> getJobStatusSummary(String jobUuid) {
        AdminFetchJob job = adminFetchJobDao.getByJobUuid(jobUuid);
        if (job == null) {
            return null;
        }

        List<Map<String, Object>> rows = adminFetchTaskDao.countStatusGroupByJobUuid(jobUuid);
        int pendingCount = 0;
        int runningCount = 0;
        int successCount = 0;
        int failureCount = 0;
        int cancelledCount = 0;
        for (Map<String, Object> row : rows) {
            String status = row.get("status") instanceof String s ? s : "";
            Number count = row.get("count") instanceof Number n ? n : 0;
            int c = count.intValue();
            switch (status) {
                case "PENDING" -> pendingCount = c;
                case "RUNNING" -> runningCount = c;
                case "SUCCESS" -> successCount = c;
                case "FAILURE" -> failureCount = c;
                case "CANCELLED" -> cancelledCount = c;
                default -> {
                    // 忽略未知状态
                }
            }
        }
        int expandedTaskCount = pendingCount + runningCount + successCount + failureCount + cancelledCount;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobUuid", jobUuid);
        result.put("status", job.getStatus());
        result.put("expandedTaskCount", expandedTaskCount);
        result.put("pendingCount", pendingCount);
        result.put("runningCount", runningCount);
        result.put("successCount", successCount);
        result.put("failureCount", failureCount);
        result.put("cancelledCount", cancelledCount);
        result.put("updatedAt", formatDateTime(job.getUpdatedAt()));
        return result;
    }

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

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("queuePending", queueStats != null ? queueStats.pending() : 0);
        summary.put("queueConsumers", queueStats != null ? queueStats.consumers() : 0);
        summary.put("runningJobs", runningJobs);
        summary.put("runningTasks", runningTasks);
        summary.put("successToday", successToday);
        summary.put("failureToday", failureToday);

        Map<String, Object> result = new LinkedHashMap<>();
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
        map.put("executionOptions", parseJson(job.getExecutionOptions()));

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

        Map<String, Object> executionOptions = parseJsonToMap(job.getExecutionOptions());
        int workerThreads = executionOptions.get("workerThreads") instanceof Number n ? n.intValue() : DEFAULT_WORKER_THREADS;
        int taskIntervalMs = executionOptions.get("taskIntervalMs") instanceof Number n ? n.intValue() : DEFAULT_TASK_INTERVAL_MS;

        Map<String, Object> dispatchStats = new LinkedHashMap<>();
        dispatchStats.put("dispatchedCount", job.getExpandedTaskCount() - pending);
        dispatchStats.put("pendingDispatchCount", pending);
        dispatchStats.put("workerThreads", workerThreads);
        dispatchStats.put("taskIntervalMs", taskIntervalMs);
        map.put("dispatchStats", dispatchStats);
        map.put("orchestrationNote", "当前由服务端异步派发，页面关闭不影响任务继续运行");

        List<world.willfrog.alphafrogmicro.common.pojo.agent.AdminFetchTask> previewTasks = adminFetchTaskDao.listByJobUuid(jobUuid, 20, 0);
        map.put("tasksPreview", previewTasks.stream().map(this::convertTaskToPreviewMap).toList());
        return map;
    }

    // ==================== 取消 ====================

    @Transactional
    public Map<String, Object> cancelJob(String jobUuid) {
        AdminFetchJob job = adminFetchJobDao.getByJobUuid(jobUuid);
        if (job == null) {
            throw new IllegalArgumentException("Job not found");
        }
        OffsetDateTime now = OffsetDateTime.now();
        int cancelledTasks = adminFetchTaskDao.cancelByJobUuid(jobUuid, "Cancelled by user", now, now);
        adminFetchJobDao.updateStatus(jobUuid, "CANCELLED", now, now);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("jobUuid", jobUuid);
        res.put("cancelledTasks", cancelledTasks);
        res.put("status", "CANCELLED");
        return res;
    }

    // ==================== 删除 ====================

    @Transactional
    public void deleteJob(String jobUuid) {
        AdminFetchJob job = adminFetchJobDao.getByJobUuid(jobUuid);
        if (job == null) {
            throw new IllegalArgumentException("Job not found");
        }
        adminFetchTaskDao.deleteByJobUuid(jobUuid);
        adminFetchJobDao.deleteByJobUuid(jobUuid);
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

        Map<String, Object> executionOptions = parseJsonToMap(job.getExecutionOptions());
        int workerThreads = executionOptions.get("workerThreads") instanceof Number n ? n.intValue() : DEFAULT_WORKER_THREADS;
        int taskIntervalMs = executionOptions.get("taskIntervalMs") instanceof Number n ? n.intValue() : DEFAULT_TASK_INTERVAL_MS;

        List<Map<String, Object>> results = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        for (String sourceUuid : failureUuids) {
            var sourceTask = adminFetchTaskDao.getByTaskUuid(sourceUuid);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("sourceTaskUuid", sourceUuid);
            if (sourceTask == null || sourceTask.getDispatchPayload() == null) {
                r.put("success", false);
                r.put("message", "source task or payload missing");
                results.add(r);
                continue;
            }
            String newTaskUuid = UUID.randomUUID().toString();
            var newTask = new world.willfrog.alphafrogmicro.common.pojo.agent.AdminFetchTask();
            newTask.setTaskUuid(newTaskUuid);
            newTask.setJobUuid(jobUuid);
            newTask.setTemplateKey(sourceTask.getTemplateKey() != null ? sourceTask.getTemplateKey() : sourceTask.getTaskName());
            newTask.setTaskName(sourceTask.getTaskName());
            newTask.setTaskSubType(sourceTask.getTaskSubType());
            newTask.setStatus("PENDING");
            newTask.setSourceKind(sourceTask.getSourceKind());
            newTask.setSourceIndex(sourceTask.getSourceIndex());
            newTask.setTaskSetMode(sourceTask.getTaskSetMode());
            newTask.setParamsSummary(sourceTask.getParamsSummary());
            newTask.setInputParams(sourceTask.getInputParams());
            // 重新生成 dispatchPayload，确保包含新的 task_uuid
            Map<String, Object> oldPayloadMap = parseJsonToMap(sourceTask.getDispatchPayload());
            Object tp = oldPayloadMap.get("task_params");
            Map<String, Object> params = new LinkedHashMap<>();
            if (tp instanceof Map<?, ?> m) {
                params = new LinkedHashMap<>((Map<String, Object>) m);
            }
            newTask.setDispatchPayload(expansionService.buildDispatchPayload(newTaskUuid, sourceTask.getTaskName(), sourceTask.getTaskSubType(), params));
            newTask.setCreatedBy(sourceTask.getCreatedBy());
            newTask.setCreatedAt(now);
            newTask.setUpdatedAt(now);
            newTask.setRetryOfTaskUuid(sourceUuid);
            adminFetchTaskDao.insert(newTask);

            r.put("newTaskUuid", newTaskUuid);
            r.put("success", true);
            results.add(r);
        }

        counterService.refreshJobCounters(jobUuid);
        expansionService.dispatchJobAsync(jobUuid, workerThreads, taskIntervalMs);

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

    private Map<String, Object> convertTaskToPreviewMap(world.willfrog.alphafrogmicro.common.pojo.agent.AdminFetchTask task) {
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
}

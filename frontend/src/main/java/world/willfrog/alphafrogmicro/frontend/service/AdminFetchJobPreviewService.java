package world.willfrog.alphafrogmicro.frontend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.frontend.service.AdminFetchJobMeta.*;
import world.willfrog.alphafrogmicro.frontend.service.AdminFetchJobExpansionService.LeafTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin Fetch Job 预览与参数分析服务。
 * 前端调用 /admin/fetch-jobs/preview 时，后端做展开校验、参数状态分析和行为摘要生成。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AdminFetchJobPreviewService {

    private final AdminFetchJobExpansionService expansionService;

    private static final int MAX_LEAF_TASKS = 5000;
    private static final int DEFAULT_WORKER_THREADS = 4;
    private static final int DEFAULT_TASK_INTERVAL_MS = 200;

    /**
     * 预览入口：校验 mode → 展开叶子任务 → 参数分析 → 行为摘要 → 组装响应。
     * 返回的 valid 字段决定前端提交按钮是否可用。
     */
    public Map<String, Object> previewJob(Map<String, Object> body) {
        List<PreviewError> errors = new ArrayList<>();
        List<PreviewWarning> warnings = new ArrayList<>();
        List<Map<String, Object>> parameterAnalysis = new ArrayList<>();
        List<Map<String, Object>> behaviorSummary = new ArrayList<>();

        String mode = expansionService.getString(body, "mode");
        if (mode == null || !Set.of("tasks", "task_sets", "fetch_info", "all").contains(mode)) {
            errors.add(new PreviewError("mode", "INVALID_MODE", "mode 必须是 tasks / task_sets / fetch_info / all 之一"));
            return buildPreviewResponse(false, errors, warnings, parameterAnalysis, behaviorSummary, body);
        }

        List<LeafTask> leafTasks;
        try {
            leafTasks = expansionService.expandJobBody(body, mode);
        } catch (IllegalArgumentException e) {
            errors.add(new PreviewError("", "EXPANSION_ERROR", e.getMessage()));
            return buildPreviewResponse(false, errors, warnings, parameterAnalysis, behaviorSummary, body);
        }

        if (leafTasks.isEmpty()) {
            errors.add(new PreviewError("", "NO_TASKS", "没有可执行的任务，请检查请求体中的 tasks / task_sets / fetch_info 配置"));
        }

        if (leafTasks.size() > MAX_LEAF_TASKS) {
            errors.add(new PreviewError("", "EXCEEDS_MAX_LEAF_TASKS",
                    "展开后的叶子任务数超过上限 " + MAX_LEAF_TASKS + "，当前 " + leafTasks.size()));
        }

        try {
            parameterAnalysis.addAll(analyzeBodyParameters(body, mode, errors, warnings));
        } catch (Exception e) {
            log.warn("Failed to analyze parameters", e);
        }

        behaviorSummary.addAll(buildBehaviorSummary(leafTasks));

        return buildPreviewResponse(errors.isEmpty(), errors, warnings, parameterAnalysis, behaviorSummary, body);
    }

    private Map<String, Object> buildPreviewResponse(boolean valid, List<PreviewError> errors,
                                                     List<PreviewWarning> warnings,
                                                     List<Map<String, Object>> parameterAnalysis,
                                                     List<Map<String, Object>> behaviorSummary,
                                                     Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", valid);
        result.put("errors", errors.stream().map(e -> Map.of("path", e.path(), "code", e.code(), "message", e.message())).toList());
        result.put("warnings", warnings.stream().map(w -> Map.of("path", w.path(), "code", w.code(), "message", w.message())).toList());
        result.put("parameterAnalysis", parameterAnalysis);
        result.put("behaviorSummary", behaviorSummary);
        result.put("executionPlan", buildExecutionPlan(body));
        return result;
    }

    private Map<String, Object> buildExecutionPlan(Map<String, Object> body) {
        Map<String, Object> opts = parseExecutionOptions(body);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("orchestrationMode", "SERVER_ASYNC");
        plan.put("workerThreads", opts.get("workerThreads"));
        plan.put("taskIntervalMs", opts.get("taskIntervalMs"));
        plan.put("note", "任务提交后由服务端继续派发，关闭页面不影响执行。");
        return plan;
    }

    /** 解析 execution_options，提供 workerThreads / taskIntervalMs 默认值及范围校验 */
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
            if (t >= 0 && t <= 300000) {
                defaults.put("taskIntervalMs", t);
            }
        }
        return defaults;
    }

    /** 对 body 中的每个任务源（tasks / task_sets / fetch_info）做参数分析 */
    private List<Map<String, Object>> analyzeBodyParameters(Map<String, Object> body, String mode,
                                                            List<PreviewError> errors, List<PreviewWarning> warnings) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (("tasks".equals(mode) || "all".equals(mode)) && body.get("tasks") instanceof List<?> tasksRaw) {
            for (int i = 0; i < tasksRaw.size(); i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = (Map<String, Object>) tasksRaw.get(i);
                result.add(analyzeSourceItem(raw, "tasks[" + i + "]", false, errors, warnings));
            }
        }
        if (("task_sets".equals(mode) || "all".equals(mode)) && body.get("task_sets") instanceof List<?> setsRaw) {
            for (int i = 0; i < setsRaw.size(); i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = (Map<String, Object>) setsRaw.get(i);
                result.add(analyzeSourceItem(raw, "task_sets[" + i + "]", true, errors, warnings));
            }
        }
        if (("fetch_info".equals(mode) || "all".equals(mode)) && body.get("fetch_info") instanceof Map<?, ?> fiRaw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fetchInfo = (Map<String, Object>) fiRaw;
            for (Map.Entry<String, Object> entry : fetchInfo.entrySet()) {
                if (!Map.of("fund", "fund_info", "stock", "stock_info", "index", "index_info").containsKey(entry.getKey())) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = entry.getValue() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                String taskName = Map.of("fund", "fund_info", "stock", "stock_info", "index", "index_info").get(entry.getKey());
                TaskVariantMeta meta = AdminFetchJobMeta.findVariantMeta(taskName, 1);
                if (meta != null) {
                    String tsm = meta.allowedTaskSetModes().isEmpty() ? null : meta.allowedTaskSetModes().get(0);
                    result.add(analyzeSourceItemWithMeta(raw, "fetch_info." + entry.getKey(), tsm, meta, errors, warnings));
                }
            }
        }
        return result;
    }

    /**
     * 分析单个任务源：先通过 taskName + subType 查找 Catalog 元数据，
     * 若找不到或 taskSetMode 不被支持则报错，否则进入字段级分析。
     */
    private Map<String, Object> analyzeSourceItem(Map<String, Object> raw, String scope, boolean isTaskSet,
                                                  List<PreviewError> errors, List<PreviewWarning> warnings) {
        String taskName = expansionService.getTaskName(raw);
        TaskVariantMeta meta;
        String taskSetMode = null;
        if (isTaskSet) {
            int taskSetSubType = expansionService.getIntValue(raw.get("task_set_sub_type"), 1);
            // 兼容旧协议
            if (raw.get("task_set_sub_type") == null && raw.get("task_sub_type") != null) {
                taskSetSubType = expansionService.getIntValue(raw.get("task_sub_type"), 1);
            }
            meta = AdminFetchJobMeta.findTaskSetVariantMeta(taskName, taskSetSubType);
            if (meta != null) {
                taskSetMode = meta.allowedTaskSetModes().isEmpty() ? null : meta.allowedTaskSetModes().get(0);
            }
            if (meta == null) {
                errors.add(new PreviewError(scope + ".task_name", "UNKNOWN_TASK",
                        "未知的任务类型: " + taskName + " (taskSetSubType=" + taskSetSubType + ")"));
            }
        } else {
            int taskSubType = expansionService.getIntValue(raw.get("task_sub_type"), 1);
            meta = AdminFetchJobMeta.findVariantMeta(taskName, taskSubType);
            if (meta == null) {
                errors.add(new PreviewError(scope + ".task_name", "UNKNOWN_TASK",
                        "未知的任务类型: " + taskName + " (subType=" + taskSubType + ")"));
            }
        }
        if (meta == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("scope", scope);
            empty.put("effective", List.of());
            empty.put("requiredMissing", List.of());
            empty.put("optionalEffectiveEmpty", List.of());
            empty.put("ignored", List.of());
            empty.put("invalid", List.of());
            return empty;
        }
        return analyzeSourceItemWithMeta(raw, scope, taskSetMode, meta, errors, warnings);
    }

    /**
     * 字段级参数分析：遍历 Catalog 中定义的所有字段，
     * 根据 effectiveWhen / requiredWhen / ignoredWhen 判断字段状态，
     * 输出 effective、requiredMissing、optionalEffectiveEmpty、ignored、invalid 五类列表。
     */
    private Map<String, Object> analyzeSourceItemWithMeta(Map<String, Object> raw, String scope, String taskSetMode,
                                                          TaskVariantMeta meta,
                                                          List<PreviewError> errors, List<PreviewWarning> warnings) {
        Map<String, Object> context = buildContext(raw, taskSetMode);
        List<String> effective = new ArrayList<>();
        List<String> requiredMissing = new ArrayList<>();
        List<String> optionalEffectiveEmpty = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        List<String> invalid = new ArrayList<>();

        for (FieldMeta field : meta.fields()) {
            Object value = getFieldValue(raw, field.name());
            boolean isEffective = evaluateCondition(field.effectiveWhen(), context);
            boolean isRequired = evaluateCondition(field.requiredWhen(), context);
            boolean isIgnored = evaluateCondition(field.ignoredWhen(), context);

            boolean isInvalid = false;
            String invalidCode = null;
            if (field.validation() != null && !isEmptyValue(value)) {
                if (field.validation().nonZero() != null && field.validation().nonZero()) {
                    int iv = parseIntSilent(value);
                    if (iv <= 0) {
                        isInvalid = true;
                        invalidCode = "INVALID_RANGE_STEP";
                    }
                }
                if (!isInvalid && field.validation().min() != null) {
                    int iv = parseIntSilent(value);
                    if (iv < field.validation().min()) {
                        isInvalid = true;
                        invalidCode = "VALIDATION_MIN_EXCEEDED";
                    }
                }
            }

            if (isInvalid) {
                invalid.add(field.name());
                errors.add(new PreviewError(scope + "." + field.name(), invalidCode != null ? invalidCode : "VALIDATION_ERROR",
                        field.label() + " 校验失败"));
            } else if (isRequired && isEmptyValue(value)) {
                requiredMissing.add(field.name());
                errors.add(new PreviewError(scope + "." + field.name(), "REQUIRED_FIELD_MISSING",
                        field.label() + " 为必填项"));
            } else if (isIgnored) {
                if (!isEmptyValue(value)) {
                    warnings.add(new PreviewWarning(scope + "." + field.name(), "IGNORED_FIELD",
                            field.label() + " 当前配置下不会生效"));
                }
                ignored.add(field.name());
            } else if (isEffective) {
                effective.add(field.name());
                if (!field.required() && isEmptyValue(value)) {
                    optionalEffectiveEmpty.add(field.name());
                }
            }
        }

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("scope", scope);
        analysis.put("effective", effective);
        analysis.put("requiredMissing", requiredMissing);
        analysis.put("optionalEffectiveEmpty", optionalEffectiveEmpty);
        analysis.put("ignored", ignored);
        analysis.put("invalid", invalid);
        return analysis;
    }

    /** 构建字段取值上下文：把嵌套 Map 扁平化，并注入 task_set_mode 等变量 */
    private Map<String, Object> buildContext(Map<String, Object> rawItem, String taskSetMode) {
        Map<String, Object> context = new java.util.HashMap<>();
        flattenMap("", rawItem, context);
        if (taskSetMode != null) {
            context.put("task_set_mode", taskSetMode);
        }
        Object tsCode = context.get("ts_code");
        if (tsCode == null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> tp = rawItem.get("task_params") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            if (tp != null) tsCode = tp.get("ts_code");
        }
        if (tsCode != null) context.put("ts_code", tsCode);
        return context;
    }

    /** 递归扁平化嵌套 Map，键名用点号连接 */
    private void flattenMap(String prefix, Map<String, Object> source, Map<String, Object> target) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) map;
                flattenMap(key, m, target);
            } else {
                target.put(key, entry.getValue());
            }
        }
    }

    /**
     * 按字段名从 raw 任务对象中取值。
     * 带点的路径直接按层级查找；无点号的字段优先在 task_params 下查找，回退到顶层。
     */
    private Object getFieldValue(Map<String, Object> raw, String fieldName) {
        if (fieldName.contains(".")) {
            String[] parts = fieldName.split("\\.");
            Object current = raw;
            for (String part : parts) {
                if (current instanceof Map<?, ?> map) {
                    current = map.get(part);
                } else {
                    return null;
                }
            }
            return current;
        } else {
            Object taskParams = raw.get("task_params");
            if (taskParams instanceof Map<?, ?> tp) {
                Object value = tp.get(fieldName);
                if (value != null) return value;
            }
            return raw.get(fieldName);
        }
    }

    /** 解析 RuleCondition：Always / Never / PathIn / PathEquals / And / Or */
    private boolean evaluateCondition(RuleCondition condition, Map<String, Object> context) {
        if (condition instanceof RuleCondition.Always) {
            return true;
        }
        if (condition instanceof RuleCondition.Never) {
            return false;
        }
        if (condition instanceof RuleCondition.PathIn pathIn) {
            Object value = context.get(pathIn.path());
            return value != null && pathIn.values().contains(String.valueOf(value));
        }
        if (condition instanceof RuleCondition.PathEquals pathEquals) {
            Object value = context.get(pathEquals.path());
            return value != null && String.valueOf(value).equals(String.valueOf(pathEquals.value()));
        }
        if (condition instanceof RuleCondition.And and) {
            return and.conditions().stream().allMatch(c -> evaluateCondition(c, context));
        }
        if (condition instanceof RuleCondition.Or or) {
            return or.conditions().stream().anyMatch(c -> evaluateCondition(c, context));
        }
        return false;
    }

    /** 根据展开后的 LeafTask 分组生成行为摘要 */
    private List<Map<String, Object>> buildBehaviorSummary(List<LeafTask> leafTasks) {
        Map<String, List<LeafTask>> grouped = leafTasks.stream()
                .collect(Collectors.groupingBy(l -> l.sourceScope != null ? l.sourceScope : (l.sourceKind + "[" + l.sourceIndex + "]")));
        List<Map<String, Object>> summary = new ArrayList<>();
        for (Map.Entry<String, List<LeafTask>> entry : grouped.entrySet()) {
            String scope = entry.getKey();
            List<LeafTask> leaves = entry.getValue();
            if (leaves.isEmpty()) continue;
            LeafTask first = leaves.get(0);
            TaskVariantMeta meta = AdminFetchJobMeta.findVariantMeta(first.taskName, first.taskSubType);
            String title = meta != null ? meta.executionSummaryTemplate() : first.taskName + " 任务";
            String description = buildBehaviorDescription(first, leaves.size());

            List<Map<String, Object>> samples = new ArrayList<>();
            for (int i = 0; i < Math.min(3, leaves.size()); i++) {
                LeafTask leaf = leaves.get(i);
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("title", "叶子请求 " + (i + 1));
                sample.put("description", expansionService.buildParamsSummary(leaf.taskName, leaf.taskParams));
                samples.add(sample);
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("scope", scope);
            item.put("title", title);
            item.put("description", description);
            item.put("expansionCount", leaves.size());
            item.put("sampleLeafRequests", samples);
            summary.add(item);
        }
        return summary;
    }

    /** 生成人类可读的行为描述，对 index_quote / index_weight 有特殊文案 */
    private String buildBehaviorDescription(LeafTask first, int count) {
        String mode = first.taskSetMode != null ? first.taskSetMode : "";
        String taskName = first.taskName != null ? first.taskName : "";
        StringBuilder sb = new StringBuilder();
        sb.append("后端会把当前配置展开为 ").append(count).append(" 条叶子抓取任务");

        boolean isIndexBatchTask = "index_quote".equals(taskName) || "index_weight".equals(taskName);
        if (isIndexBatchTask) {
            switch (mode) {
                case "trade_dates" -> sb.append("，按交易日逐日展开，每个日期生成若干本地指数批次叶子任务。");
                case "offsets" -> sb.append("，按本地指数批次展开，每个叶子任务处理一批指数代码。");
                case "trade_dates_with_offsets" -> sb.append("，按交易日与本地指数批次笛卡尔积展开，每个叶子任务处理一批指数代码，内部再按 TuShare 分页参数逐页请求。");
                case "date_range_with_offsets" -> sb.append("，按固定日期范围与本地指数批次展开，每个叶子任务处理一批指数代码，内部再按 TuShare 分页参数逐页请求。");
                default -> sb.append("，每个叶子任务处理一批指数代码。");
            }
            sb.append("（受 TuShare 接口限制，需逐个指数代码请求）");
            return sb.toString();
        }

        switch (mode) {
            case "trade_dates" -> sb.append("，按交易日逐日展开。");
            case "offsets" -> sb.append("，按 offset 步进展开。");
            case "trade_dates_with_offsets" -> sb.append("，按日期与 offset 笛卡尔积展开。");
            case "date_range_with_offsets" -> sb.append("，按固定日期范围与 offset 步进展开。");
            default -> sb.append("。");
        }
        return sb.toString();
    }

    private int parseIntSilent(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean isEmptyValue(Object value) {
        if (value == null) return true;
        if (value instanceof String s) return s.isBlank();
        return false;
    }
}

package world.willfrog.alphafrogmicro.frontend.service;

import world.willfrog.alphafrogmicro.common.fetchcatalog.*;

import java.util.*;

/**
 * Admin Fetch Job 结构化元数据定义（Catalog）。
 * 从 JSON 配置动态加载，维护所有支持的任务名称、子类型、允许的任务组模式、字段列表及校验规则。
 * 前端通过 /admin/fetch-catalog 拉取此处定义，用于渲染表单和参数校验。
 */
public final class AdminFetchJobMeta {

    private AdminFetchJobMeta() {}

    /** 任务变体元数据：一个 taskName + taskSubType 的唯一描述 */
    public record TaskVariantMeta(
            String taskName,
            int taskSubType,
            String label,
            String description,
            List<String> allowedTaskSetModes,
            List<FieldMeta> fields,
            String executionSummaryTemplate
    ) {}

    /** 字段元数据：描述表单中一个输入项的校验与展示规则 */
    public record FieldMeta(
            String name,
            String label,
            String inputType,
            Object defaultValue,
            String description,
            boolean required,
            RuleCondition effectiveWhen,
            RuleCondition requiredWhen,
            RuleCondition ignoredWhen,
            FieldValidation validation
    ) {}

    /** 规则条件：用于描述字段在何种 task_set_mode 下生效、必填或被忽略 */
    public sealed interface RuleCondition {
        record Always() implements RuleCondition {}
        record Never() implements RuleCondition {}
        record PathIn(String path, List<String> values) implements RuleCondition {}
        record PathEquals(String path, Object value) implements RuleCondition {}
        record And(List<RuleCondition> conditions) implements RuleCondition {}
        record Or(List<RuleCondition> conditions) implements RuleCondition {}
    }

    public record FieldValidation(Integer min, Integer max, Boolean nonZero, String pattern) {}

    public record PreviewError(String path, String code, String message) {}
    public record PreviewWarning(String path, String code, String message) {}

    // 缓存：从 JSON 配置动态构建
    private static List<TaskVariantMeta> TASK_VARIANT_METAS = List.of();
    private static List<TaskVariantMeta> TASK_SET_VARIANT_METAS = List.of();
    /** 任务名称到中文标签的缓存，由 refresh() 时从 JSON label 字段构建 */
    private static Map<String, String> TASK_LABEL_CACHE = Map.of();
    /** 所有已注册的 expandStrategy 集合，由 refresh() 从全量 JSON 配置中收集 */
    private static List<String> ALL_EXPAND_STRATEGIES = List.of();

    /**
     * 由 FetchCatalogConfigLoader 在启动完成后调用，将 JSON 配置转换为内存中的元数据。
     */
    public static synchronized void refresh(FetchCatalogConfigLoader loader) {
        List<TaskVariantMeta> taskList = new ArrayList<>();
        List<TaskVariantMeta> taskSetList = new ArrayList<>();
        Map<String, String> labelMap = new HashMap<>();

        // 先收集所有 expandStrategy，供后续 buildFieldsFromVariant 使用
        Set<String> strategySet = new LinkedHashSet<>();
        for (FetchDataTypeConfig cfg : loader.getAllConfigs().values()) {
            if (cfg.getTaskSetVariants() != null) {
                for (TaskSetVariantConfig tsc : cfg.getTaskSetVariants()) {
                    if (tsc.getExpandStrategy() != null) strategySet.add(tsc.getExpandStrategy());
                }
            }
        }
        ALL_EXPAND_STRATEGIES = List.copyOf(strategySet);

        for (FetchDataTypeConfig config : loader.getAllConfigs().values()) {
            String dataType = config.getDataType();
            labelMap.put(dataType, config.getLabel() != null ? config.getLabel() : dataType);

            // tasks 场景变体
            if (config.getTaskVariants() != null) {
                for (TaskVariantConfig tvc : config.getTaskVariants()) {
                    List<String> allowedModes = new ArrayList<>();
                    // 查找哪些 taskSetVariants 会输出到这个 taskVariant
                    if (config.getTaskSetVariants() != null) {
                        for (TaskSetVariantConfig tsc : config.getTaskSetVariants()) {
                            if (tsc.getOutputTaskVariantSubType() == tvc.getSubType()) {
                                allowedModes.add(tsc.getExpandStrategy());
                            }
                        }
                    }
                    List<FieldMeta> fields = buildFieldsFromVariant(tvc.getRequiredParams(), tvc.getOptionalParams(), tvc.getParamDefs(), null);
                    taskList.add(new TaskVariantMeta(
                            dataType,
                            tvc.getSubType(),
                            tvc.getLabel(),
                            tvc.getDescription(),
                            Collections.unmodifiableList(allowedModes),
                            Collections.unmodifiableList(fields),
                            tvc.getDescription()
                    ));
                }
            }

            // task_sets 场景变体
            if (config.getTaskSetVariants() != null) {
                for (TaskSetVariantConfig tsc : config.getTaskSetVariants()) {
                    List<String> allowedModes = List.of(tsc.getExpandStrategy());
                    List<FieldMeta> fields = buildFieldsFromVariant(tsc.getRequiredParams(), tsc.getOptionalParams(), tsc.getParamDefs(), tsc.getExpandStrategy());
                    taskSetList.add(new TaskVariantMeta(
                            dataType,
                            tsc.getSubType(),
                            tsc.getLabel(),
                            tsc.getDescription(),
                            allowedModes,
                            Collections.unmodifiableList(fields),
                            tsc.getExpandDescription()
                    ));
                }
            }
        }

        TASK_VARIANT_METAS = Collections.unmodifiableList(taskList);
        TASK_SET_VARIANT_METAS = Collections.unmodifiableList(taskSetList);
        TASK_LABEL_CACHE = Collections.unmodifiableMap(labelMap);
    }

    /**
     * 根据参数定义和任务变体上下文构建 FieldMeta 列表。
     * taskSetExpandStrategy 仅在构建 task_sets 变体时传入，用于推导 effectiveWhen/ignoredWhen。
     */
    private static List<FieldMeta> buildFieldsFromVariant(List<String> requiredParams, List<String> optionalParams,
                                                          Map<String, ParamDef> paramDefs, String taskSetExpandStrategy) {
        List<FieldMeta> fields = new ArrayList<>();
        if (paramDefs == null) return fields;

        Set<String> requiredSet = requiredParams != null ? new HashSet<>(requiredParams) : Set.of();
        Set<String> optionalSet = optionalParams != null ? new HashSet<>(optionalParams) : Set.of();

        for (Map.Entry<String, ParamDef> entry : paramDefs.entrySet()) {
            String paramName = entry.getKey();
            ParamDef def = entry.getValue();
            String inputType = "string".equals(def.getType()) ? "string" : "number";

            RuleCondition effectiveWhen = always();
            RuleCondition ignoredWhen = never();

            if (taskSetExpandStrategy != null) {
                // 根据参数路径和当前 expandStrategy 推导生效条件
                List<String> activeModes = computeActiveModes(paramName, taskSetExpandStrategy);
                if (activeModes != null && !activeModes.isEmpty()) {
                    effectiveWhen = pathIn("task_set_mode", activeModes.toArray(new String[0]));
                    // ignoredWhen = 不在 activeModes 中的其他模式（从已加载配置动态获取）
                    List<String> ignoredModes = new ArrayList<>();
                    for (String m : ALL_EXPAND_STRATEGIES) {
                        if (!activeModes.contains(m)) ignoredModes.add(m);
                    }
                    if (!ignoredModes.isEmpty()) {
                        ignoredWhen = pathIn("task_set_mode", ignoredModes.toArray(new String[0]));
                    } else {
                        ignoredWhen = never();
                    }
                }
            }

            RuleCondition requiredWhen = requiredSet.contains(paramName) ? always() : never();

            // 步长校验：对于 offset_range.step 或 api_offset_step，要求 > 0
            FieldValidation validation = null;
            if (paramName.endsWith(".step") || paramName.endsWith("_step")) {
                validation = new FieldValidation(1, null, true, null);
            }

            fields.add(new FieldMeta(
                    paramName,
                    def.getLabel() != null ? def.getLabel() : paramName,
                    inputType,
                    def.getDefaultValue(),
                    def.getDescription() != null ? def.getDescription() : "",
                    false,
                    effectiveWhen,
                    requiredWhen,
                    ignoredWhen,
                    validation
            ));
        }
        return fields;
    }

    /**
     * 根据参数路径和当前变体的 expandStrategy，推断该字段在哪些 task_set_mode 下生效。
     */
    private static List<String> computeActiveModes(String paramName, String currentExpandStrategy) {
        // 当前模式本身一定生效
        List<String> modes = new ArrayList<>();
        modes.add(currentExpandStrategy);

        // trade_dates 相关参数：在 trade_dates、trade_dates_with_offsets、trade_dates_with_index_batches 下生效
        if (paramName.startsWith("trade_dates.")) {
            return List.of("trade_dates", "trade_dates_with_offsets", "trade_dates_with_index_batches");
        }
        // date_range 相关参数：在 date_range_with_offsets、date_range_with_index_batches、date_range_with_api_offsets 下生效
        if (paramName.startsWith("date_range.")) {
            return List.of("date_range_with_offsets", "date_range_with_index_batches", "date_range_with_api_offsets");
        }
        // offset_range 相关参数：在 offsets、trade_dates_with_offsets、date_range_with_offsets 下生效
        if (paramName.startsWith("offset_range.")) {
            return List.of("offsets", "trade_dates_with_offsets", "date_range_with_offsets");
        }
        // index_batches 相关参数：在 index_batches、trade_dates_with_index_batches、date_range_with_index_batches 下生效
        if (paramName.equals("index_count_limit") || paramName.equals("index_batch_limit")) {
            return List.of("index_batches", "trade_dates_with_index_batches", "date_range_with_index_batches");
        }
        // 通用参数（offset, limit, api_offset_* 等）：在当前模式下总是生效
        return List.of(currentExpandStrategy);
    }

    // ==================== 公共查询 API ====================

    /**
     * 获取 tasks 场景下的所有变体元数据。
     */
    public static List<TaskVariantMeta> getVariantMetas() {
        return TASK_VARIANT_METAS;
    }

    /**
     * 获取 task_sets 场景下的所有变体元数据。
     */
    public static List<TaskVariantMeta> getTaskSetVariantMetas() {
        return TASK_SET_VARIANT_METAS;
    }

    /** 按 taskName + taskSubType 查找 tasks 变体元数据，找不到返回 null */
    public static TaskVariantMeta findVariantMeta(String taskName, int taskSubType) {
        for (TaskVariantMeta vm : TASK_VARIANT_METAS) {
            if (vm.taskName().equals(taskName) && vm.taskSubType() == taskSubType) {
                return vm;
            }
        }
        return null;
    }

    /** 按 taskName + taskSetSubType 查找 task_sets 变体元数据，找不到返回 null */
    public static TaskVariantMeta findTaskSetVariantMeta(String taskName, int taskSetSubType) {
        for (TaskVariantMeta vm : TASK_SET_VARIANT_METAS) {
            if (vm.taskName().equals(taskName) && vm.taskSubType() == taskSetSubType) {
                return vm;
            }
        }
        return null;
    }

    /** 任务名称到中文标签的映射（从 JSON 配置的 label 字段动态读取，缓存未命中时回退到原始 taskName） */
    public static String taskLabel(String taskName) {
        String label = TASK_LABEL_CACHE.get(taskName);
        return label != null ? label : taskName;
    }

    // ==================== 辅助工厂方法（保留兼容） ====================

    public static FieldMeta field(String name, String label, String inputType,
                                   RuleCondition effectiveWhen, RuleCondition requiredWhen,
                                   FieldValidation validation, RuleCondition ignoredWhen, Object defaultValue) {
        return new FieldMeta(name, label, inputType, defaultValue, "", false, effectiveWhen, requiredWhen, ignoredWhen, validation);
    }

    public static RuleCondition always() { return new RuleCondition.Always(); }
    public static RuleCondition never() { return new RuleCondition.Never(); }
    public static RuleCondition pathIn(String path, String... values) {
        return new RuleCondition.PathIn(path, List.of(values));
    }
}

package world.willfrog.alphafrogmicro.frontend.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Admin Fetch Job 结构化元数据定义
 */
public final class AdminFetchJobMeta {

    private AdminFetchJobMeta() {}

    public record TaskVariantMeta(
            String taskName,
            int taskSubType,
            String label,
            String description,
            List<String> allowedTaskSetModes,
            List<FieldMeta> fields,
            String executionSummaryTemplate
    ) {}

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

    private static final List<TaskVariantMeta> VARIANT_METAS = buildVariantMetas();

    public static List<TaskVariantMeta> getVariantMetas() {
        return VARIANT_METAS;
    }

    public static TaskVariantMeta findVariantMeta(String taskName, int taskSubType) {
        for (TaskVariantMeta vm : VARIANT_METAS) {
            if (vm.taskName().equals(taskName) && vm.taskSubType() == taskSubType) {
                return vm;
            }
        }
        return null;
    }

    public static String taskLabel(String taskName) {
        return switch (taskName) {
            case "stock_daily" -> "股票日线";
            case "stock_quote" -> "股票行情";
            case "index_quote" -> "指数行情";
            case "index_weight" -> "指数权重";
            case "index_daily_basic" -> "指数日线指标";
            case "fund_nav" -> "基金净值";
            case "fund_portfolio" -> "基金持仓";
            case "fund_share" -> "基金份额";
            case "etf_share_size" -> "ETF 份额规模";
            case "trade_calendar" -> "交易日历";
            case "sw_industry_daily" -> "申万行业日线";
            case "sw_industry_classify" -> "申万行业分类";
            case "sw_industry_member" -> "申万行业成分";
            case "ci_index_member" -> "中证指数成分";
            case "fund_manager" -> "基金经理";
            case "fund_info" -> "基金基本信息";
            case "stock_info" -> "股票基本信息";
            case "index_info" -> "指数基本信息";
            default -> taskName;
        };
    }

    private static List<TaskVariantMeta> buildVariantMetas() {
        List<TaskVariantMeta> list = new ArrayList<>();
        List<String> all4Modes = List.of("trade_dates", "offsets", "trade_dates_with_offsets", "date_range_with_offsets");

        list.add(vm("stock_daily", 1, "股票日线（明细）", "按日期或 offset 抓取股票日线明细", all4Modes,
                stockDailyFields(false), "股票日线将按交易日逐日展开，每个日期生成一条叶子抓取任务。"));
        list.add(vm("stock_daily", 2, "股票日线（汇总）", "按日期或 offset 抓取股票日线汇总", all4Modes,
                stockDailyFields(false), "股票日线将按交易日逐日展开，每个日期生成一条叶子抓取任务。"));

        list.add(vm("stock_quote", 1, "股票行情（明细）", "按日期或 offset 抓取股票行情", all4Modes,
                commonRangeFields(false, true), "股票行情将按交易日逐日展开，每个日期生成一条叶子抓取任务。"));
        list.add(vm("stock_quote", 2, "股票行情（汇总）", "按日期或 offset 抓取股票行情汇总", all4Modes,
                commonRangeFields(false, true), "股票行情将按交易日逐日展开，每个日期生成一条叶子抓取任务。"));

        list.add(vm("index_quote", 1, "指数行情（明细）", "按日期或 offset 抓取指数行情", all4Modes,
                indexQuoteFields(false, true), "指数行情将按交易日与指数批次笛卡尔积展开，每个叶子任务处理一批指数代码。"));
        list.add(vm("index_quote", 2, "指数行情（汇总）", "按日期或 offset 抓取指数行情汇总", all4Modes,
                indexQuoteFields(false, true), "指数行情将按交易日与指数批次笛卡尔积展开，每个叶子任务处理一批指数代码。"));

        list.add(vm("index_weight", 1, "指数权重", "按日期或 offset 抓取指数权重", all4Modes,
                indexWeightFields(false, true), "指数权重将按日期范围与指数批次展开，每个叶子任务处理一批指数代码。"));

        list.add(vm("index_daily_basic", 1, "指数日线指标（明细）", "按日期或 offset 抓取指数日线指标", all4Modes,
                commonRangeFields(true, false), "指数日线指标将按交易日逐日展开，每个日期生成一条叶子抓取任务。"));
        list.add(vm("index_daily_basic", 2, "指数日线指标（汇总）", "按日期或 offset 抓取指数日线指标汇总", all4Modes,
                commonRangeFields(true, false), "指数日线指标将按交易日逐日展开，每个日期生成一条叶子抓取任务。"));

        list.add(vm("fund_nav", 1, "基金净值（明细）", "按日期或 offset 抓取基金净值", all4Modes,
                commonRangeFields(false, true), "基金净值将按交易日逐日展开，每个日期生成一条叶子抓取任务。"));
        list.add(vm("fund_nav", 2, "基金净值（汇总）", "按日期或 offset 抓取基金净值汇总", all4Modes,
                commonRangeFields(false, true), "基金净值将按交易日逐日展开，每个日期生成一条叶子抓取任务。"));

        list.add(vm("fund_portfolio", 1, "基金持仓", "按日期或 offset 抓取基金持仓", all4Modes,
                commonRangeFields(false, true), "基金持仓将按交易日逐日展开，每个日期生成一条叶子抓取任务。"));

        list.add(vm("fund_share", 1, "基金份额", "按日期抓取基金份额", all4Modes,
                commonRangeFields(true, false), "基金份额将按交易日逐日展开，每个日期生成一条叶子抓取任务。"));

        list.add(vm("etf_share_size", 1, "ETF 份额规模", "按日期抓取 ETF 份额规模", all4Modes,
                commonRangeFields(true, false), "ETF 份额规模将按交易日逐日展开，每个日期生成一条叶子抓取任务。"));

        list.add(vm("trade_calendar", 1, "交易日历", "按日期范围抓取交易日历", List.of("date_range_with_offsets"),
                commonRangeFields(false, true), "交易日历将按 offset 步进逐条抓取指定日期范围的数据。"));

        list.add(vm("sw_industry_daily", 1, "申万行业日线", "按日期抓取申万行业日线", all4Modes,
                commonRangeFields(true, false), "申万行业日线将按交易日逐日展开，每个日期生成一条叶子抓取任务。"));

        // Empty params tasks
        list.add(vm("sw_industry_classify", 1, "申万行业分类", "抓取申万行业分类", List.of(),
                List.of(), "申万行业分类将一次性抓取全部数据。"));
        list.add(vm("sw_industry_member", 1, "申万行业成分", "抓取申万行业成分", List.of(),
                List.of(), "申万行业成分将一次性抓取全部数据。"));
        list.add(vm("ci_index_member", 1, "中证指数成分", "抓取中证指数成分", List.of(),
                List.of(), "中证指数成分将一次性抓取全部数据。"));
        list.add(vm("fund_manager", 1, "基金经理", "抓取基金经理信息", List.of(),
                List.of(), "基金经理信息将一次性抓取全部数据。"));

        // Info tasks
        List<FieldMeta> infoFields = List.of(
                field("offset", "偏移量", "number", always(), never(), null, never(), 0),
                field("limit", "每页条数", "number", always(), never(), null, never(), 5000),
                field("market", "市场", "string", always(), never(), null, never(), "E")
        );
        list.add(vm("fund_info", 1, "基金基本信息", "抓取基金基本信息", List.of(),
                infoFields, "基金基本信息将按市场过滤一次性抓取。"));
        list.add(vm("stock_info", 1, "股票基本信息", "抓取股票基本信息", List.of(),
                infoFields, "股票基本信息将按市场过滤一次性抓取。"));
        list.add(vm("index_info", 1, "指数基本信息", "抓取指数基本信息", List.of(),
                infoFields, "指数基本信息将按市场过滤一次性抓取。"));

        return Collections.unmodifiableList(list);
    }

    private static TaskVariantMeta vm(String taskName, int taskSubType, String label, String description,
                                      List<String> allowedModes, List<FieldMeta> fields, String summary) {
        return new TaskVariantMeta(taskName, taskSubType, label, description, allowedModes, fields, summary);
    }

    public static FieldMeta field(String name, String label, String inputType,
                                   RuleCondition effectiveWhen, RuleCondition requiredWhen,
                                   FieldValidation validation, RuleCondition ignoredWhen, Object defaultValue) {
        return new FieldMeta(name, label, inputType, defaultValue, "", false, effectiveWhen, requiredWhen, ignoredWhen, validation);
    }

    public static FieldMeta field(String name, String label, String inputType,
                                   RuleCondition effectiveWhen, RuleCondition requiredWhen,
                                   FieldValidation validation, RuleCondition ignoredWhen, Object defaultValue, String description) {
        return new FieldMeta(name, label, inputType, defaultValue, description, false, effectiveWhen, requiredWhen, ignoredWhen, validation);
    }

    public static List<FieldMeta> commonRangeFields(boolean useStringDate, boolean hasDateStrParams) {
        List<FieldMeta> fields = new ArrayList<>();
        RuleCondition dateModes = pathIn("task_set_mode", "trade_dates", "trade_dates_with_offsets");
        RuleCondition offsetModes = pathIn("task_set_mode", "offsets", "trade_dates_with_offsets", "date_range_with_offsets");
        RuleCondition fixedDateModes = pathIn("task_set_mode", "date_range_with_offsets");
        RuleCondition offsetFieldIgnored = pathIn("task_set_mode", "offsets", "trade_dates_with_offsets", "date_range_with_offsets");
        RuleCondition stringDateIgnoredModes = pathIn("task_set_mode", "trade_dates", "trade_dates_with_offsets", "date_range_with_offsets");

        fields.add(field("trade_dates.start_timestamp", "开始日期时间戳", "number", dateModes, dateModes, null, never(), null));
        fields.add(field("trade_dates.end_timestamp", "结束日期时间戳", "number", dateModes, dateModes, null, never(), null));
        fields.add(field("date_range.start_date", "开始日期", "string", fixedDateModes, fixedDateModes, null, never(), null));
        fields.add(field("date_range.end_date", "结束日期", "string", fixedDateModes, fixedDateModes, null, never(), null));
        fields.add(field("offset_range.start", "Offset 起始", "number", offsetModes, offsetModes, null, never(), null));
        fields.add(field("offset_range.end", "Offset 结束", "number", offsetModes, offsetModes, null, never(), null));
        fields.add(field("offset_range.step", "Offset 步长", "number", offsetModes, offsetModes,
                new FieldValidation(1, null, true, null), never(), null));
        fields.add(field("limit", "每页条数", "number", always(), never(), null, never(), 5000));
        fields.add(field("offset", "偏移量", "number", always(), never(), null, offsetFieldIgnored, 0));

        if (useStringDate) {
            fields.add(field("trade_date", "交易日(YYYYMMDD)", "string", always(), never(), null,
                    pathIn("task_set_mode", "trade_dates", "trade_dates_with_offsets", "date_range_with_offsets"), null));
        } else {
            fields.add(field("trade_date_timestamp", "交易日时间戳", "number", always(), never(), null,
                    pathIn("task_set_mode", "trade_dates", "trade_dates_with_offsets", "date_range_with_offsets"), null));
        }
        if (hasDateStrParams && !useStringDate) {
            fields.add(field("start_date", "开始日期", "string", always(), never(), null, stringDateIgnoredModes, null));
            fields.add(field("end_date", "结束日期", "string", always(), never(), null, stringDateIgnoredModes, null));
        }
        return fields;
    }

    public static List<FieldMeta> stockDailyFields(boolean useStringDate) {
        List<FieldMeta> fields = new ArrayList<>();
        RuleCondition dateModes = pathIn("task_set_mode", "trade_dates", "trade_dates_with_offsets");
        RuleCondition offsetModes = pathIn("task_set_mode", "offsets", "trade_dates_with_offsets", "date_range_with_offsets");
        RuleCondition fixedDateModes = pathIn("task_set_mode", "date_range_with_offsets");

        fields.add(field("trade_dates.start_timestamp", "开始日期时间戳", "number", dateModes, dateModes, null, never(), null));
        fields.add(field("trade_dates.end_timestamp", "结束日期时间戳", "number", dateModes, dateModes, null, never(), null));
        fields.add(field("date_range.start_date", "开始日期", "string", fixedDateModes, fixedDateModes, null, never(), null));
        fields.add(field("date_range.end_date", "结束日期", "string", fixedDateModes, fixedDateModes, null, never(), null));
        fields.add(field("offset_range.start", "Offset 起始", "number", offsetModes, offsetModes, null, never(), null));
        fields.add(field("offset_range.end", "Offset 结束", "number", offsetModes, offsetModes, null, never(), null));
        fields.add(field("offset_range.step", "Offset 步长", "number", offsetModes, offsetModes,
                new FieldValidation(1, null, true, null), never(), null));
        fields.add(field("limit", "每页条数", "number", always(), never(), null, never(), 5000));
        fields.add(field("offset", "偏移量", "number", always(), never(), null, offsetModes, 0));
        fields.add(field("trade_date_timestamp", "交易日时间戳", "number", always(), never(), null,
                pathIn("task_set_mode", "trade_dates", "trade_dates_with_offsets", "date_range_with_offsets"), null));
        fields.add(field("start_date_timestamp", "开始日期时间戳", "number", always(), never(), null,
                pathIn("task_set_mode", "date_range_with_offsets"), null));
        fields.add(field("end_date_timestamp", "结束日期时间戳", "number", always(), never(), null,
                pathIn("task_set_mode", "date_range_with_offsets"), null));
        return fields;
    }

    public static List<FieldMeta> indexQuoteFields(boolean useStringDate, boolean hasDateStrParams) {
        List<FieldMeta> fields = new ArrayList<>(commonRangeFields(useStringDate, hasDateStrParams));
        for (int i = 0; i < fields.size(); i++) {
            FieldMeta f = fields.get(i);
            if ("limit".equals(f.name())) {
                fields.set(i, field("limit", "指数批次大小", "number", f.effectiveWhen(), f.requiredWhen(), f.validation(), f.ignoredWhen(), f.defaultValue(),
                        "由于 TuShare index_daily 接口要求逐个指数代码请求，limit 表示每个叶子任务从本地指数库中取出的指数数量上限。"));
            } else if ("offset".equals(f.name())) {
                fields.set(i, field("offset", "指数起始偏移", "number", f.effectiveWhen(), f.requiredWhen(), f.validation(), f.ignoredWhen(), f.defaultValue(),
                        "指数列表的起始偏移量，配合 offset_range 可实现指数维度分批。"));
            }
        }
        fields.add(field("api_limit", "TuShare 分页限制", "number", always(), never(), null, never(), 0,
                "预留字段：当 TuShare index_daily 接口支持按 ts_code 维度分页时生效，当前暂不起作用。"));
        fields.add(field("api_offset", "TuShare 分页偏移", "number", always(), never(), null, never(), 0,
                "预留字段：当 TuShare index_daily 接口支持按 ts_code 维度分页时生效，当前暂不起作用。"));
        return fields;
    }

    public static List<FieldMeta> indexWeightFields(boolean useStringDate, boolean hasDateStrParams) {
        List<FieldMeta> fields = new ArrayList<>(commonRangeFields(useStringDate, hasDateStrParams));
        for (int i = 0; i < fields.size(); i++) {
            FieldMeta f = fields.get(i);
            if ("limit".equals(f.name())) {
                fields.set(i, field("limit", "指数批次大小", "number", f.effectiveWhen(), f.requiredWhen(), f.validation(), f.ignoredWhen(), f.defaultValue(),
                        "由于 TuShare index_weight 接口要求逐个指数代码请求，limit 表示每个叶子任务从本地指数库中取出的指数数量上限。"));
            } else if ("offset".equals(f.name())) {
                fields.set(i, field("offset", "指数起始偏移", "number", f.effectiveWhen(), f.requiredWhen(), f.validation(), f.ignoredWhen(), f.defaultValue(),
                        "指数列表的起始偏移量，配合 offset_range 可实现指数维度分批。"));
            }
        }
        fields.add(field("api_limit", "TuShare 分页限制", "number", always(), never(), null, never(), 0,
                "预留字段：当 TuShare index_weight 接口支持按 index_code 维度分页时生效，当前暂不起作用。"));
        fields.add(field("api_offset", "TuShare 分页偏移", "number", always(), never(), null, never(), 0,
                "预留字段：当 TuShare index_weight 接口支持按 index_code 维度分页时生效，当前暂不起作用。"));
        return fields;
    }

    public static RuleCondition always() { return new RuleCondition.Always(); }
    public static RuleCondition never() { return new RuleCondition.Never(); }
    public static RuleCondition pathIn(String path, String... values) {
        return new RuleCondition.PathIn(path, List.of(values));
    }
}

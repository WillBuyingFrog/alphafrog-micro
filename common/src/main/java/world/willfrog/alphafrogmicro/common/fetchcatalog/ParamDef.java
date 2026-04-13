package world.willfrog.alphafrogmicro.common.fetchcatalog;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON 配置中参数定义的 POJO。
 * <p>每个参数定义对应 paramDefs Map 中的一个 value，key 即参数名称。
 * 描述一个参数的展示标签、功能说明、数据类型（string / number / boolean）、格式约束及默认值。</p>
 *
 * <p>示例 JSON片段：</p>
 * <pre>{@code
 * "start_date": {
 *   "label": "开始日期",
 *   "description": "日期范围的开始日期",
 *   "type": "string",
 *   "format": "yyyyMMdd",
 *   "default": null
 * }
 * }</pre>
 *
 * @see TaskVariantConfig#getParamDefs()
 * @see TaskSetVariantConfig#getParamDefs()
 */
public class ParamDef {

    /** 参数展示标签，用于前端表单渲染 */
    @JsonProperty("label")
    private String label;

    /** 参数功能描述 */
    @JsonProperty("description")
    private String description;

    /** 参数数据类型，支持 string / number / boolean */
    @JsonProperty("type")
    private String type;

    /** 格式约束，如日期参数的 yyyyMMdd */
    @JsonProperty("format")
    private String format;

    /** 默认值，类型与 JSON 一致（可能为 String、Number、null 等） */
    @JsonProperty("default")
    private Object defaultValue;

    // ==================== Getters & Setters ====================

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
    }
}

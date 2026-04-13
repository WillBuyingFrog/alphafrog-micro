package world.willfrog.alphafrogmicro.common.fetchcatalog;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * tasks 场景下的参数组合配置（对应 {@code task_sub_type}）。
 * <p>
 * 每个 taskVariant 代表一种任务执行方式，通过 {@link #subType} 区分。
 * 例如 {@code index_quote} 有 subType=1（按交易日）、subType=2（按日期范围）、subType=3（历史初始化）。
 * </p>
 *
 * @see FetchDataTypeConfig
 * @see TaskSetVariantConfig
 */
public class TaskVariantConfig {

    /** task_sub_type 编号，使用包装类型以便区分"未设置"和"值为0" */
    @JsonProperty("subType")
    private Integer subType;

    @JsonProperty("label")
    private String label;

    @JsonProperty("description")
    private String description;

    @JsonProperty("requiredParams")
    private List<String> requiredParams;

    @JsonProperty("optionalParams")
    private List<String> optionalParams;

    @JsonProperty("paramDefs")
    private Map<String, ParamDef> paramDefs;

    @JsonProperty("specialFlags")
    private List<String> specialFlags;

    // ==================== Getters & Setters ====================

    public int getSubType() {
        return subType != null ? subType : 0;
    }

    public void setSubType(Integer subType) {
        this.subType = subType;
    }

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

    public List<String> getRequiredParams() {
        return requiredParams;
    }

    public void setRequiredParams(List<String> requiredParams) {
        this.requiredParams = requiredParams;
    }

    public List<String> getOptionalParams() {
        return optionalParams;
    }

    public void setOptionalParams(List<String> optionalParams) {
        this.optionalParams = optionalParams;
    }

    public Map<String, ParamDef> getParamDefs() {
        return paramDefs;
    }

    public void setParamDefs(Map<String, ParamDef> paramDefs) {
        this.paramDefs = paramDefs;
    }

    public List<String> getSpecialFlags() {
        return specialFlags;
    }

    public void setSpecialFlags(List<String> specialFlags) {
        this.specialFlags = specialFlags;
    }

    /**
     * 判断当前变体是否包含指定特殊标记。
     */
    public boolean hasFlag(String flag) {
        return specialFlags != null && specialFlags.contains(flag);
    }
}

package world.willfrog.alphafrogmicro.common.fetchcatalog;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * task_sets 场景下的展开配置（对应 task_set_sub_type）。
 */
public class TaskSetVariantConfig {

    @JsonProperty("subType")
    private int subType;

    @JsonProperty("label")
    private String label;

    @JsonProperty("description")
    private String description;

    @JsonProperty("expandParam")
    private String expandParam;

    @JsonProperty("expandDescription")
    private String expandDescription;

    @JsonProperty("expandStrategy")
    private String expandStrategy;

    @JsonProperty("requiredParams")
    private List<String> requiredParams;

    @JsonProperty("optionalParams")
    private List<String> optionalParams;

    @JsonProperty("paramDefs")
    private Map<String, ParamDef> paramDefs;

    @JsonProperty("specialFlags")
    private List<String> specialFlags;

    @JsonProperty("outputTaskVariantSubType")
    private int outputTaskVariantSubType;

    // ==================== Getters & Setters ====================

    public int getSubType() {
        return subType;
    }

    public void setSubType(int subType) {
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

    public String getExpandParam() {
        return expandParam;
    }

    public void setExpandParam(String expandParam) {
        this.expandParam = expandParam;
    }

    public String getExpandDescription() {
        return expandDescription;
    }

    public void setExpandDescription(String expandDescription) {
        this.expandDescription = expandDescription;
    }

    public String getExpandStrategy() {
        return expandStrategy;
    }

    public void setExpandStrategy(String expandStrategy) {
        this.expandStrategy = expandStrategy;
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

    public int getOutputTaskVariantSubType() {
        return outputTaskVariantSubType;
    }

    public void setOutputTaskVariantSubType(int outputTaskVariantSubType) {
        this.outputTaskVariantSubType = outputTaskVariantSubType;
    }

    /**
     * 判断当前变体是否包含指定特殊标记。
     */
    public boolean hasFlag(String flag) {
        return specialFlags != null && specialFlags.contains(flag);
    }
}

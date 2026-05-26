package world.willfrog.alphafrogmicro.common.fetchcatalog;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 单个数据类型的完整 JSON 配置，对应 fetch-catalog/ 目录下的一个 JSON 文件。
 * <p>包含数据类型标识、中文标签、描述、归属的服务模块，以及全量的 taskVariants（单任务变体）
 * 和 taskSetVariants（任务集展开变体）定义。</p>
 *
 * @see FetchCatalogConfigLoader
 * @see TaskVariantConfig
 * @see TaskSetVariantConfig
 */
public class FetchDataTypeConfig {

    @JsonProperty("dataType")
    private String dataType;

    @JsonProperty("label")
    private String label;

    @JsonProperty("description")
    private String description;

    @JsonProperty("serviceModule")
    private String serviceModule;

    @JsonProperty("taskVariants")
    private List<TaskVariantConfig> taskVariants;

    @JsonProperty("taskSetVariants")
    private List<TaskSetVariantConfig> taskSetVariants;

    // ==================== Getters & Setters ====================

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
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

    public String getServiceModule() {
        return serviceModule;
    }

    public void setServiceModule(String serviceModule) {
        this.serviceModule = serviceModule;
    }

    public List<TaskVariantConfig> getTaskVariants() {
        return taskVariants;
    }

    public void setTaskVariants(List<TaskVariantConfig> taskVariants) {
        this.taskVariants = taskVariants;
    }

    public List<TaskSetVariantConfig> getTaskSetVariants() {
        return taskSetVariants;
    }

    public void setTaskSetVariants(List<TaskSetVariantConfig> taskSetVariants) {
        this.taskSetVariants = taskSetVariants;
    }

    /**
     * 根据 subType 查找 task variant 配置。
     */
    public TaskVariantConfig findTaskVariant(int subType) {
        if (taskVariants == null) return null;
        for (TaskVariantConfig v : taskVariants) {
            if (v.getSubType() == subType) {
                return v;
            }
        }
        return null;
    }

    /**
     * 根据 subType 查找 task set variant 配置。
     */
    public TaskSetVariantConfig findTaskSetVariant(int subType) {
        if (taskSetVariants == null) return null;
        for (TaskSetVariantConfig v : taskSetVariants) {
            if (v.getSubType() == subType) {
                return v;
            }
        }
        return null;
    }
}

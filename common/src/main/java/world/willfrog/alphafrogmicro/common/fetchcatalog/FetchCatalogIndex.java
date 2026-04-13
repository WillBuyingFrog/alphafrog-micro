package world.willfrog.alphafrogmicro.common.fetchcatalog;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * catalog-index.json 的根对象。
 * <p>该索引文件定义了所有已注册的数据类型及其对应的 JSON 配置文件名，
 * 由 {@link FetchCatalogConfigLoader} 在 @PostConstruct 阶段加载，作为批量读取各数据类型配置的入口。</p>
 *
 * <p>示例 JSON：</p>
 * <pre>{@code
 * {
 *   "version": "1.0",
 *   "dataTypes": [
 *     { "name": "index_quote", "file": "index_quote.json" }
 *   ]
 * }
 * }</pre>
 */
public class FetchCatalogIndex {

    @JsonProperty("version")
    private String version;

    @JsonProperty("dataTypes")
    private List<DataTypeEntry> dataTypes;

    // ==================== Getters & Setters ====================

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public List<DataTypeEntry> getDataTypes() {
        return dataTypes;
    }

    public void setDataTypes(List<DataTypeEntry> dataTypes) {
        this.dataTypes = dataTypes;
    }

    /**
     * 索引中的单条数据类型条目，描述 name 与 JSON 文件名的映射关系。
     */
    public static class DataTypeEntry {

        @JsonProperty("name")
        private String name;

        @JsonProperty("file")
        private String file;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getFile() {
            return file;
        }

        public void setFile(String file) {
            this.file = file;
        }
    }
}

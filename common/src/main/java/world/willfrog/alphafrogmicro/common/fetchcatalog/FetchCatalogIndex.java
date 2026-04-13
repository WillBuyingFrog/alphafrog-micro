package world.willfrog.alphafrogmicro.common.fetchcatalog;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * catalog-index.json 的根对象。
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
     * 索引中的单条数据类型条目。
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

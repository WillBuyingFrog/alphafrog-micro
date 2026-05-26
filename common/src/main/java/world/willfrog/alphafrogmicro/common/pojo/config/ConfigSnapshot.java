package world.willfrog.alphafrogmicro.common.pojo.config;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 配置快照（版本链）
 */
@Data
public class ConfigSnapshot {
    private Integer id;
    private Integer typeId;
    private String version;
    private String contentJson;
    private String contentMd5;
    private String comment;
    private String createdBy;
    private OffsetDateTime createdAt;
}

package world.willfrog.alphafrogmicro.common.pojo.config;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ConfigAuditLog {

    private Integer id;
    private Integer typeId;
    private String action;
    private Integer snapshotId;
    private String baseVersion;
    private String operatorId;
    private String reason;
    private OffsetDateTime createdAt;
}

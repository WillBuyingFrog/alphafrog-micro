package world.willfrog.alphafrogmicro.common.pojo.config;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 当前激活版本
 */
@Data
public class ConfigActive {
    private Integer typeId;
    private Integer snapshotId;
    private OffsetDateTime activatedAt;
    private String activatedBy;
}

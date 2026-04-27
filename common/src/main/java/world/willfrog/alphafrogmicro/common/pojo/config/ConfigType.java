package world.willfrog.alphafrogmicro.common.pojo.config;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 配置类型注册
 */
@Data
public class ConfigType {
    private Integer id;
    private String name;
    private String dataId;
    private String configGroup;
    private String serviceName;
    private String schemaJson;
    private String description;
    private OffsetDateTime createdAt;
}

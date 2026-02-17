package world.willfrog.alphafrogmicro.common.pojo.agent;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class AdminAuditLog {
    private Long id;
    private String auditId;
    private String operatorId;
    private String action;
    private String targetType;
    private String targetId;
    private String beforeJson;
    private String afterJson;
    private String reason;
    private String idempotencyKey;
    private OffsetDateTime createdAt;
}

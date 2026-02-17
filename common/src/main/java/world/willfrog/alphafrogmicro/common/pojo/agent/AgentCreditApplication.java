package world.willfrog.alphafrogmicro.common.pojo.agent;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class AgentCreditApplication {
    private Long id;
    private String applicationId;
    private String userId;
    private Integer amount;
    private String reason;
    private String contact;
    private String status;
    private String processedBy;
    private String processReason;
    private Integer version;
    private String ext;
    private OffsetDateTime createdAt;
    private OffsetDateTime processedAt;
}

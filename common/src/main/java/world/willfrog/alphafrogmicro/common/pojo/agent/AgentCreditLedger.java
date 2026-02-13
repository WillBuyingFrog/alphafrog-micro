package world.willfrog.alphafrogmicro.common.pojo.agent;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class AgentCreditLedger {
    private Long id;
    private String ledgerId;
    private String userId;
    private String bizType;
    private Integer delta;
    private Integer balanceBefore;
    private Integer balanceAfter;
    private String sourceType;
    private String sourceId;
    private String operatorId;
    private String idempotencyKey;
    private String ext;
    private OffsetDateTime createdAt;
}

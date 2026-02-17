package world.willfrog.alphafrogmicro.common.pojo.agent;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class AdminIdempotency {
    private Long id;
    private String operatorId;
    private String action;
    private String targetId;
    private String idempotencyKey;
    private String requestHash;
    private String status;
    private String responseJson;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

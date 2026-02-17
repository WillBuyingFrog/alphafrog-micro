package world.willfrog.alphafrogmicro.common.pojo.user;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class UserInviteCode {
    private Long id;
    private String inviteCode;
    private Long createdBy;
    private Long usedBy;
    private String status;
    private String ext;
    private OffsetDateTime createdAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime usedAt;
}

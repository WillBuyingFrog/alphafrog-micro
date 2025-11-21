package world.willfrog.alphafrogmicro.portfolioservice.domain;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class PortfolioPo {
    private Long id;
    private String userId;
    private String name;
    private String visibility;
    private String tagsJson;
    private String status;
    private String timezone;
    private String extJson;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

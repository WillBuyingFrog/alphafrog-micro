package world.willfrog.alphafrogmicro.portfolioservice.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class PortfolioResponse {
    private Long id;
    private String userId;
    private String name;
    private String visibility;
    private List<String> tags;
    private String status;
    private String timezone;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

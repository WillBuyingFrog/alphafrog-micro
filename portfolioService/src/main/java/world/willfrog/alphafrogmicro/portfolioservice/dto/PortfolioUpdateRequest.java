package world.willfrog.alphafrogmicro.portfolioservice.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class PortfolioUpdateRequest {
    @Size(max = 255)
    private String name;

    @Size(max = 32)
    private String visibility;

    private List<@Size(max = 64) String> tags;

    @Size(max = 32)
    private String status;
}

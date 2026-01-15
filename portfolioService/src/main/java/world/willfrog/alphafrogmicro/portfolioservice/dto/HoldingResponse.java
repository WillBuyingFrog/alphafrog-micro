package world.willfrog.alphafrogmicro.portfolioservice.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@Builder
public class HoldingResponse {
    private Long id;
    private Long portfolioId;
    private String symbol;
    private String symbolType;
    private String exchange;
    private String positionSide;
    private BigDecimal quantity;
    private BigDecimal avgCost;
    private OffsetDateTime updatedAt;
}

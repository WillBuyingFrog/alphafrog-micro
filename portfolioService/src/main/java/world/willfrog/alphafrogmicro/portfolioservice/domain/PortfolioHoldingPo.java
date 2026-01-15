package world.willfrog.alphafrogmicro.portfolioservice.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class PortfolioHoldingPo {
    private Long id;
    private Long portfolioId;
    private String userId;
    private String symbol;
    private String symbolType;
    private String exchange;
    private String positionSide;
    private BigDecimal quantity;
    private BigDecimal avgCost;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private String extJson;
}

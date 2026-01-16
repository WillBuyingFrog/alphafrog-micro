package world.willfrog.alphafrogmicro.portfolioservice.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@Builder
public class StrategyTargetResponse {
    private Long id;
    private Long strategyId;
    private String symbol;
    private String symbolType;
    private BigDecimal targetWeight;
    private LocalDate effectiveDate;
    private OffsetDateTime updatedAt;
}

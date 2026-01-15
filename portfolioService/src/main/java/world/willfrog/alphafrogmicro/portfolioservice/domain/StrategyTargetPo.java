package world.willfrog.alphafrogmicro.portfolioservice.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class StrategyTargetPo {
    private Long id;
    private Long strategyId;
    private String userId;
    private String symbol;
    private String symbolType;
    private BigDecimal targetWeight;
    private LocalDate effectiveDate;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private String extJson;
}

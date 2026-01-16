package world.willfrog.alphafrogmicro.portfolioservice.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@Builder
public class StrategyResponse {
    private Long id;
    private Long portfolioId;
    private String userId;
    private String name;
    private String description;
    private String ruleJson;
    private String rebalanceRule;
    private BigDecimal capitalBase;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private String baseCurrency;
    private String benchmarkSymbol;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

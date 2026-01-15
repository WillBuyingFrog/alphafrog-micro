package world.willfrog.alphafrogmicro.portfolioservice.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class StrategyDefinitionPo {
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
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private String extJson;
}

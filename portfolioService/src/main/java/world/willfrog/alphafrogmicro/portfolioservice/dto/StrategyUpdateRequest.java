package world.willfrog.alphafrogmicro.portfolioservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class StrategyUpdateRequest {
    @Size(max = 255)
    private String name;

    @Size(max = 2000)
    private String description;

    @Size(max = 8000)
    private String ruleJson;

    @Size(max = 255)
    private String rebalanceRule;

    @DecimalMin("0.00")
    private BigDecimal capitalBase;

    private LocalDate startDate;

    private LocalDate endDate;

    @Size(max = 32)
    private String status;

    @Size(max = 16)
    private String baseCurrency;

    @Size(max = 64)
    private String benchmarkSymbol;
}

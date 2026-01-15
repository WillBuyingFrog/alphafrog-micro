package world.willfrog.alphafrogmicro.portfolioservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class StrategyTargetUpsertItem {
    @NotBlank
    @Size(max = 64)
    private String symbol;

    @NotBlank
    @Size(max = 32)
    private String symbolType;

    @NotNull
    @DecimalMin("0.000000")
    private BigDecimal targetWeight;

    private LocalDate effectiveDate;
}

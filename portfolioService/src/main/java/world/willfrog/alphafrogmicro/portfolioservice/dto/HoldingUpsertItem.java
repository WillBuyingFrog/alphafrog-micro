package world.willfrog.alphafrogmicro.portfolioservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class HoldingUpsertItem {
    @NotBlank
    @Size(max = 64)
    private String symbol;

    @NotBlank
    @Size(max = 32)
    private String symbolType;

    @Size(max = 32)
    private String exchange;

    @Size(max = 16)
    private String positionSide;

    @NotNull
    @DecimalMin("0.00")
    private BigDecimal quantity;

    @NotNull
    @DecimalMin("0.00")
    private BigDecimal avgCost;
}

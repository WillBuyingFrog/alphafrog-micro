package world.willfrog.alphafrogmicro.portfolioservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class TradeCreateItem {
    @NotBlank
    @Size(max = 64)
    private String symbol;

    @NotBlank
    @Size(max = 32)
    private String eventType;

    @NotNull
    @DecimalMin("0.00")
    private BigDecimal quantity;

    @DecimalMin("0.00")
    private BigDecimal price;

    @DecimalMin("0.00")
    private BigDecimal fee;

    private BigDecimal slippage;

    @NotNull
    private OffsetDateTime tradeTime;

    private OffsetDateTime settleDate;

    @Size(max = 500)
    private String note;

    private String payloadJson;
}

package world.willfrog.alphafrogmicro.portfolioservice.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@Builder
public class TradeResponse {
    private Long id;
    private Long portfolioId;
    private String symbol;
    private String eventType;
    private BigDecimal quantity;
    private BigDecimal price;
    private BigDecimal fee;
    private BigDecimal slippage;
    private OffsetDateTime tradeTime;
    private OffsetDateTime settleDate;
    private String note;
}

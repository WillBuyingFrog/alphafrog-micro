package world.willfrog.alphafrogmicro.portfolioservice.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class PortfolioTradePo {
    private Long id;
    private Long portfolioId;
    private String userId;
    private String symbol;
    private String eventType;
    private BigDecimal quantity;
    private BigDecimal price;
    private BigDecimal fee;
    private BigDecimal slippage;
    private OffsetDateTime tradeTime;
    private OffsetDateTime settleDate;
    private String note;
    private String payloadJson;
    private OffsetDateTime createdAt;
}

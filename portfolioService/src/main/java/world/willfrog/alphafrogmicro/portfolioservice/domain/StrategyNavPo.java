package world.willfrog.alphafrogmicro.portfolioservice.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class StrategyNavPo {
    private Long id;
    private Long runId;
    private String userId;
    private LocalDate tradeDate;
    private BigDecimal nav;
    private BigDecimal returnPct;
    private BigDecimal benchmarkNav;
    private BigDecimal drawdown;
    private OffsetDateTime createdAt;
}

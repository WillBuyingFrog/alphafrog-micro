package world.willfrog.alphafrogmicro.portfolioservice.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class StrategyNavResponse {
    private Long id;
    private Long runId;
    private LocalDate tradeDate;
    private BigDecimal nav;
    private BigDecimal returnPct;
    private BigDecimal benchmarkNav;
    private BigDecimal drawdown;
}

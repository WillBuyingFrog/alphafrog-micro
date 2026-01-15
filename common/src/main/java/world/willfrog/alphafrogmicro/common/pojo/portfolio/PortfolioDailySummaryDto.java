package world.willfrog.alphafrogmicro.common.pojo.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PortfolioDailySummaryDto {

    private Long portfolioId;
    private String portfolioName;
    private LocalDate date;

    private BigDecimal totalMarketValue;
    private BigDecimal previousTotalMarketValue;
    private BigDecimal initialCost;

    private BigDecimal dailyReturn;
    private BigDecimal dailyReturnRate;
    private BigDecimal cumulativeReturnRate;

    private List<HoldingPerformanceDto> holdingPerformances;
    private List<HoldingPerformanceDto> topGainers;
    private List<HoldingPerformanceDto> topLosers;

    private List<String> alerts;

    private List<PortfolioPerformanceRelativeToDateDto> performanceRelativeToDates;

} 
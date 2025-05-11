package world.willfrog.alphafrogmicro.portfolioservice.dto;

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
    private LocalDate date; // 摘要对应的日期

    private BigDecimal totalMarketValue;        // 组合今日总市值
    private BigDecimal previousTotalMarketValue; // 组合昨日总市值 (用于计算日回报率)
    private BigDecimal initialCost;             // 组合初始投入成本 (用于计算累计回报率)

    private BigDecimal dailyReturn;             // 组合日度回报金额
    private BigDecimal dailyReturnRate;         // 组合日度回报率
    private BigDecimal cumulativeReturnRate;    // 组合累计回报率

    private List<HoldingPerformanceDto> holdingPerformances; // 各持仓当日表现详情
    private List<HoldingPerformanceDto> topGainers;          // 组合内当日涨幅最大的股票/基金
    private List<HoldingPerformanceDto> topLosers;           // 组合内当日跌幅最大的股票/基金

    private List<String> alerts; // 相关预警信息 (例如，若持仓个股触发"市场异动监测服务"中的警报)

} 
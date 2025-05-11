package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.AssetType;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.Portfolio;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.PortfolioHolding;
import world.willfrog.alphafrogmicro.portfolioservice.dto.HoldingPerformanceDto;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PortfolioDailySummaryDto;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetInfo;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetPriceInfo;
import world.willfrog.alphafrogmicro.portfolioservice.service.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Optional;

@Service
public class PortfolioSummaryServiceImpl implements PortfolioSummaryService {

    private static final Logger logger = LoggerFactory.getLogger(PortfolioSummaryServiceImpl.class);
    private static final int DEFAULT_SCALE = 4; // 用于汇率
    private static final int MONEY_SCALE = 2;   // 用于货币价值
    private static final int TOP_N_MOVERS = 3; // 显示表现最好/最差的资产数量

    private final PortfolioService portfolioService;
    private final AssetPriceService assetPriceService;
    private final AssetInfoService assetInfoService;
    private final PortfolioCalculatorService calculatorService;

    
    public PortfolioSummaryServiceImpl(PortfolioService portfolioService,
                                       AssetPriceService assetPriceService,
                                       AssetInfoService assetInfoService,
                                       PortfolioCalculatorService calculatorService) {
        this.portfolioService = portfolioService;
        this.assetPriceService = assetPriceService;
        this.assetInfoService = assetInfoService;
        this.calculatorService = calculatorService;
    }

    @Override
    public PortfolioDailySummaryDto generateDailySummary(Long portfolioId, LocalDate date) {
        Optional<Portfolio> portfolioOpt = portfolioService.getPortfolioWithHoldingsById(portfolioId);
        if (portfolioOpt.isEmpty()) {
            logger.warn("Portfolio not found with ID: {}", portfolioId);
            // 或者抛出一个特定的 PortfolioNotFoundException
            return null; 
        }
        Portfolio portfolio = portfolioOpt.get();
        List<PortfolioHolding> holdings = portfolio.getHoldings();
        if (holdings == null || holdings.isEmpty()) {
            logger.info("Portfolio ID: {} has no holdings. Returning empty summary.", portfolioId);
            PortfolioDailySummaryDto emptySummary = new PortfolioDailySummaryDto();
            emptySummary.setPortfolioId(portfolioId);
            emptySummary.setPortfolioName(portfolio.getName());
            emptySummary.setDate(date);
            emptySummary.setHoldingPerformances(Collections.emptyList());
            emptySummary.setTopGainers(Collections.emptyList());
            emptySummary.setTopLosers(Collections.emptyList());
            emptySummary.setAlerts(Collections.emptyList());
            return emptySummary;
        }

        // 1. 准备用于批量获取的资产标识符
        Map<String, AssetType> assetIdentifiersWithTypes = holdings.stream()
                .collect(Collectors.toMap(PortfolioHolding::getAssetIdentifier, PortfolioHolding::getAssetType, (t1,t2) -> t1)); // 如果出现重复，则取第一个

        // 2. 获取所有资产价格（当日和前一日）
        // 对于前一日的价格，AssetPriceService 需要处理查找正确的前一个交易日的逻辑
        Map<String, AssetPriceInfo> assetPricesCurrent = assetPriceService.getMultipleAssetPriceInfo(assetIdentifiersWithTypes, date);
        // TODO: 正确确定前一个*交易*日。目前，假设 date.minusDays(1) 由 AssetPriceService 处理，或者对于一个简单的开始是可以的
        LocalDate previousDate = date.minusDays(1); // 这是一个简化。实际实现需要交易日历。
        Map<String, AssetPriceInfo> assetPricesPreviousDayForMarketValue = assetPriceService.getMultipleAssetPriceInfo(assetIdentifiersWithTypes, previousDate);

        // 3. 获取所有资产信息（名称）
        Map<String, AssetInfo> assetInfos = assetInfoService.getMultipleAssetInfo(assetIdentifiersWithTypes);

        // 4. 计算每个持仓的表现
        List<HoldingPerformanceDto> holdingPerformances = new ArrayList<>();
        for (PortfolioHolding holding : holdings) {
            AssetPriceInfo currentPriceInfo = assetPricesCurrent.get(holding.getAssetIdentifier());
            AssetInfo info = assetInfos.get(holding.getAssetIdentifier());

            if (currentPriceInfo == null || info == null) {
                logger.warn("Missing price or info for asset: {}. Skipping for summary.", holding.getAssetIdentifier());
                continue;
            }
            HoldingPerformanceDto perfDto = calculatorService.calculateHoldingPerformance(holding, info, currentPriceInfo);
            if (perfDto != null) {
                holdingPerformances.add(perfDto);
            }
        }

        // 5. 计算投资组合级别的指标
        PortfolioDailySummaryDto summaryDto = new PortfolioDailySummaryDto();
        summaryDto.setPortfolioId(portfolioId);
        summaryDto.setPortfolioName(portfolio.getName());
        summaryDto.setDate(date);
        summaryDto.setHoldingPerformances(holdingPerformances);

        BigDecimal initialCost = calculatorService.calculatePortfolioInitialCost(holdings);
        summaryDto.setInitialCost(initialCost);

        BigDecimal currentTotalMarketValue = calculatorService.calculatePortfolioMarketValue(holdings, assetPricesCurrent);
        summaryDto.setTotalMarketValue(currentTotalMarketValue);
        
        // 对于每日回报率：（今日总市值 - 昨日总市值）/ 昨日总市值
        // 我们需要所有持仓昨日的收盘价来计算昨日总市值。
        // assetPricesCurrent 中的 AssetPriceInfo 应包含每项资产前一天的价格，以计算每日的个体回报。
        // 对于投资组合的每日回报率，我们汇总各个持仓的当前市值和前一日市值。

        BigDecimal previousDayTotalMarketValue = BigDecimal.ZERO;
        if (!assetPricesCurrent.isEmpty()) { // 确保我们有价格数据可用
            BigDecimal tempPrevMarketValue = BigDecimal.ZERO;
            for(PortfolioHolding holding : holdings){
                AssetPriceInfo priceInfo = assetPricesCurrent.get(holding.getAssetIdentifier());
                if(priceInfo != null && priceInfo.getPreviousPrice() != null && holding.getQuantity() != null){
                    tempPrevMarketValue = tempPrevMarketValue.add(holding.getQuantity().multiply(priceInfo.getPreviousPrice()));
                }
            }
            previousDayTotalMarketValue = tempPrevMarketValue.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        summaryDto.setPreviousTotalMarketValue(previousDayTotalMarketValue);

        if (previousDayTotalMarketValue.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal dailyReturnAmount = currentTotalMarketValue.subtract(previousDayTotalMarketValue);
            summaryDto.setDailyReturn(dailyReturnAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            summaryDto.setDailyReturnRate(dailyReturnAmount.divide(previousDayTotalMarketValue, DEFAULT_SCALE, RoundingMode.HALF_UP));
        } else {
            summaryDto.setDailyReturn(BigDecimal.ZERO);
            summaryDto.setDailyReturnRate(BigDecimal.ZERO);
            if (currentTotalMarketValue.compareTo(BigDecimal.ZERO) > 0) { // 如果今天有市值但昨天没有（例如，投资组合的第一天）
                 logger.info("Previous day total market value is zero for portfolio ID: {}. Daily return rate set to 0.", portfolioId);
                 // 对于第一天，如果需要，每日回报可以基于初始成本，但这里遵循公式。
            }
        }

        if (initialCost.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal cumulativeReturn = currentTotalMarketValue.subtract(initialCost);
            summaryDto.setCumulativeReturnRate(cumulativeReturn.divide(initialCost, DEFAULT_SCALE, RoundingMode.HALF_UP));
        } else {
            summaryDto.setCumulativeReturnRate(BigDecimal.ZERO);
        }

        // 6. 确定表现最好/最差的 N 个资产
        // 按 dailyReturnRate 排序（表现好的降序，表现差的升序）
        // 过滤掉回报率为零的资产，以避免在许多静态资产的情况下将其列出
        List<HoldingPerformanceDto> sortedPerformances = holdingPerformances.stream()
            .filter(p -> p.getDailyReturnRate() != null && p.getDailyReturnRate().compareTo(BigDecimal.ZERO) != 0)
            .collect(Collectors.toList());

        summaryDto.setTopGainers(sortedPerformances.stream()
            .sorted(Comparator.comparing(HoldingPerformanceDto::getDailyReturnRate).reversed())
            .limit(TOP_N_MOVERS)
            .collect(Collectors.toList()));

        summaryDto.setTopLosers(sortedPerformances.stream()
            .sorted(Comparator.comparing(HoldingPerformanceDto::getDailyReturnRate))
            .limit(TOP_N_MOVERS)
            .collect(Collectors.toList()));

        // 7. 集成警报（占位符）
        // List<String> alerts = marketAlertIntegrationService.getAlertsForPortfolio(portfolioId, date);
        summaryDto.setAlerts(Collections.emptyList()); // 用实际警报替换

        return summaryDto;
    }
} 
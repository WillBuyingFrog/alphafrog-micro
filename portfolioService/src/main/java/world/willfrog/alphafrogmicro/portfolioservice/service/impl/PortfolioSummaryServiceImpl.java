package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.AssetType;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.Portfolio;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.PortfolioHolding;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.HoldingPerformanceDto;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.PortfolioDailySummaryDto;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.PortfolioPerformanceRelativeToDateDto;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetInfo;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetPriceInfo;
import world.willfrog.alphafrogmicro.portfolioservice.service.*;
import world.willfrog.alphafrogmicro.common.service.CommonCalendarService;

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
    private static final int DEFAULT_SCALE = 4; // 用于汇率和回报率
    private static final int MONEY_SCALE = 2;   // 用于货币价值
    private static final int TOP_N_MOVERS = 3; // 显示表现最好/最差的资产数量

    private final PortfolioService portfolioService;
    private final AssetPriceService assetPriceService;
    private final AssetInfoService assetInfoService;
    private final PortfolioCalculatorService calculatorService;
    private final CommonCalendarService commonCalendarService;

    
    public PortfolioSummaryServiceImpl(PortfolioService portfolioService,
                                       AssetPriceService assetPriceService,
                                       AssetInfoService assetInfoService,
                                       PortfolioCalculatorService calculatorService,
                                       CommonCalendarService commonCalendarService) {
        this.portfolioService = portfolioService;
        this.assetPriceService = assetPriceService;
        this.assetInfoService = assetInfoService;
        this.calculatorService = calculatorService;
        this.commonCalendarService = commonCalendarService;
    }

    @Override
    public PortfolioDailySummaryDto generateDailySummary(Long portfolioId, LocalDate currentDate, List<LocalDate> comparisonDates) {
        // 根据portfolioId获取投资组合信息
        Optional<Portfolio> portfolioOpt = portfolioService.getPortfolioWithHoldingsById(portfolioId);
        if (portfolioOpt.isEmpty()) {
            logger.warn("Portfolio not found with ID: {}", portfolioId);
            return null; 
        }
        Portfolio portfolio = portfolioOpt.get();
        List<PortfolioHolding> holdings = portfolio.getHoldings();
        
        // 如果投资组合没有持仓，返回空摘要
        if (holdings == null || holdings.isEmpty()) {
            logger.info("Portfolio ID: {} has no holdings. Returning empty summary.", portfolioId);
            PortfolioDailySummaryDto emptySummary = new PortfolioDailySummaryDto();
            emptySummary.setPortfolioId(portfolioId);
            emptySummary.setPortfolioName(portfolio.getName());
            emptySummary.setDate(currentDate);
            emptySummary.setHoldingPerformances(Collections.emptyList());
            emptySummary.setTopGainers(Collections.emptyList());
            emptySummary.setTopLosers(Collections.emptyList());
            emptySummary.setAlerts(Collections.emptyList());
            emptySummary.setPerformanceRelativeToDates(Collections.emptyList());
            return emptySummary;
        }

        // 将持仓信息转换为资产标识符和资产类型的映射
        Map<String, AssetType> assetIdentifiersWithTypes = holdings.stream()
                .collect(Collectors.toMap(PortfolioHolding::getAssetIdentifier, PortfolioHolding::getAssetType, (t1,t2) -> t1));

        // 获取当前日期的资产价格信息
        Map<String, AssetPriceInfo> assetPricesCurrent = assetPriceService.getMultipleAssetPriceInfo(assetIdentifiersWithTypes, currentDate);

        // 获取资产基本信息
        Map<String, AssetInfo> assetInfos = assetInfoService.getMultipleAssetInfo(assetIdentifiersWithTypes);

        // 计算每个持仓的表现
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

        // 创建并填充每日摘要DTO
        PortfolioDailySummaryDto summaryDto = new PortfolioDailySummaryDto();
        summaryDto.setPortfolioId(portfolioId);
        summaryDto.setPortfolioName(portfolio.getName());
        summaryDto.setDate(currentDate);
        summaryDto.setHoldingPerformances(holdingPerformances);

        // 计算投资组合的初始成本
        BigDecimal initialCost = calculatorService.calculatePortfolioInitialCost(holdings);
        summaryDto.setInitialCost(initialCost);

        // 计算当前总市值
        BigDecimal currentTotalMarketValue = calculatorService.calculatePortfolioMarketValue(holdings, assetPricesCurrent);
        summaryDto.setTotalMarketValue(currentTotalMarketValue);
        
        // 计算前一日总市值
        BigDecimal previousDayTotalMarketValue = BigDecimal.ZERO;
        if (!assetPricesCurrent.isEmpty()) {
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

        // 计算每日回报
        if (previousDayTotalMarketValue.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal dailyReturnAmount = currentTotalMarketValue.subtract(previousDayTotalMarketValue);
            summaryDto.setDailyReturn(dailyReturnAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            summaryDto.setDailyReturnRate(dailyReturnAmount.divide(previousDayTotalMarketValue, DEFAULT_SCALE, RoundingMode.HALF_UP));
        } else {
            summaryDto.setDailyReturn(BigDecimal.ZERO);
            summaryDto.setDailyReturnRate(BigDecimal.ZERO);
            if (currentTotalMarketValue.compareTo(BigDecimal.ZERO) > 0) {
                 logger.info("Previous day total market value is zero for portfolio ID: {}. Daily return rate set to 0 for date {}.", portfolioId, currentDate);
            }
        }

        // 计算累计回报率
        if (initialCost.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal cumulativeReturn = currentTotalMarketValue.subtract(initialCost);
            summaryDto.setCumulativeReturnRate(cumulativeReturn.divide(initialCost, DEFAULT_SCALE, RoundingMode.HALF_UP));
        } else {
            summaryDto.setCumulativeReturnRate(BigDecimal.ZERO);
        }

        // 筛选并排序持仓表现，用于获取表现最好和最差的资产
        List<HoldingPerformanceDto> sortedPerformances = holdingPerformances.stream()
            .filter(p -> p.getDailyReturnRate() != null && p.getDailyReturnRate().compareTo(BigDecimal.ZERO) != 0)
            .collect(Collectors.toList());

        // 设置表现最好的资产
        summaryDto.setTopGainers(sortedPerformances.stream()
            .sorted(Comparator.comparing(HoldingPerformanceDto::getDailyReturnRate).reversed())
            .limit(TOP_N_MOVERS)
            .collect(Collectors.toList()));

        // 设置表现最差的资产
        summaryDto.setTopLosers(sortedPerformances.stream()
            .sorted(Comparator.comparing(HoldingPerformanceDto::getDailyReturnRate))
            .limit(TOP_N_MOVERS)
            .collect(Collectors.toList()));

        // 初始化警报列表
        summaryDto.setAlerts(Collections.emptyList());

        // 处理历史日期比较
        if (comparisonDates != null && !comparisonDates.isEmpty()) {
            List<PortfolioPerformanceRelativeToDateDto> historicalPerformances = new ArrayList<>();
            for (LocalDate historicDate : comparisonDates) {
                if (historicDate.isAfter(currentDate) || historicDate.isEqual(currentDate)) {
                    logger.warn("Comparison date {} for portfolio ID {} is not before current date {}. Skipping.", historicDate, portfolioId, currentDate);
                    continue;
                }

                // 获取历史日期的资产价格信息
                Map<String, AssetPriceInfo> assetPricesAtHistoricDate = assetPriceService.getMultipleAssetPriceInfo(assetIdentifiersWithTypes, historicDate);
                BigDecimal marketValueAtHistoricDate = BigDecimal.ZERO;

                // 计算历史日期的总市值
                if (!assetPricesAtHistoricDate.isEmpty() && holdings != null) {
                    BigDecimal tempMarketValue = BigDecimal.ZERO;
                    for (PortfolioHolding holding : holdings) {
                        AssetPriceInfo priceInfo = assetPricesAtHistoricDate.get(holding.getAssetIdentifier());
                        if (priceInfo != null && priceInfo.getCurrentPrice() != null && holding.getQuantity() != null) {
                            tempMarketValue = tempMarketValue.add(holding.getQuantity().multiply(priceInfo.getCurrentPrice()));
                        } else {
                             logger.debug("Missing price for asset {} on historic date {} for portfolio ID {}. Treating value as 0 for this date.", holding.getAssetIdentifier(), historicDate, portfolioId);
                        }
                    }
                    marketValueAtHistoricDate = tempMarketValue.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                }
                
                PortfolioPerformanceRelativeToDateDto performanceDto = new PortfolioPerformanceRelativeToDateDto();
                performanceDto.setReferenceDate(historicDate);
                performanceDto.setMarketValueAtReferenceDate(marketValueAtHistoricDate);

                if (marketValueAtHistoricDate.compareTo(BigDecimal.ZERO) != 0) {
                    BigDecimal returnAmount = currentTotalMarketValue.subtract(marketValueAtHistoricDate);
                    performanceDto.setReturnAmountSinceReferenceDate(returnAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
                    performanceDto.setReturnRateSinceReferenceDate(returnAmount.divide(marketValueAtHistoricDate, DEFAULT_SCALE, RoundingMode.HALF_UP));
                } else {
                    // 如果历史市值是0，回报额就是当前市值（假设历史市值前的投入可以忽略或已反映在初始成本中）
                    // 或者，也可以认为回报额为0，回报率为0，这里选择前者来表示从0开始的增长值
                    performanceDto.setReturnAmountSinceReferenceDate(currentTotalMarketValue.setScale(MONEY_SCALE, RoundingMode.HALF_UP)); 
                    performanceDto.setReturnRateSinceReferenceDate(BigDecimal.ZERO); // 避免除以零
                    if (currentTotalMarketValue.compareTo(BigDecimal.ZERO) > 0) {
                        logger.info("Market value at historic date {} was zero for portfolio ID: {}. Return rate set to 0 for comparison with {}.", historicDate, portfolioId, currentDate);
                    }
                }
                historicalPerformances.add(performanceDto);
            }
            summaryDto.setPerformanceRelativeToDates(historicalPerformances);
        } else {
            summaryDto.setPerformanceRelativeToDates(Collections.emptyList());
        }

        return summaryDto;
    }
}


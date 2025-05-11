package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.PortfolioHolding;
import world.willfrog.alphafrogmicro.portfolioservice.dto.HoldingPerformanceDto;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetInfo;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetPriceInfo;
import world.willfrog.alphafrogmicro.portfolioservice.service.PortfolioCalculatorService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@Service
public class PortfolioCalculatorServiceImpl implements PortfolioCalculatorService {

    private static final Logger logger = LoggerFactory.getLogger(PortfolioCalculatorServiceImpl.class);
    private static final int DEFAULT_SCALE = 4; // 对百分比值的保留小数位数
    private static final int MONEY_SCALE = 2;   // 对货币值的保留小数位数

    @Override
    public HoldingPerformanceDto calculateHoldingPerformance(PortfolioHolding holding, AssetInfo assetInfo, AssetPriceInfo priceInfo) {
        if (holding == null || assetInfo == null || priceInfo == null || priceInfo.getCurrentPrice() == null || holding.getQuantity() == null || holding.getAverageCostPrice() == null) {
            logger.warn("Missing data for holding performance calculation: holdingId={}, assetIdentifier={}, assetInfo={}, priceInfo={}",
                        holding != null ? holding.getId() : "null",
                        holding != null ? holding.getAssetIdentifier() : "null",
                        assetInfo, priceInfo);
            return null;
        }

        HoldingPerformanceDto.HoldingPerformanceDtoBuilder performanceDtoBuilder = HoldingPerformanceDto.builder();
        performanceDtoBuilder.assetIdentifier(holding.getAssetIdentifier());
        performanceDtoBuilder.assetType(holding.getAssetType());
        performanceDtoBuilder.assetName(assetInfo.getAssetName());
        performanceDtoBuilder.quantity(holding.getQuantity());

        BigDecimal averageCostPrice = holding.getAverageCostPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        performanceDtoBuilder.averageCostPrice(averageCostPrice);

        BigDecimal initialCost = holding.getQuantity().multiply(averageCostPrice);
        performanceDtoBuilder.initialCost(initialCost.setScale(MONEY_SCALE, RoundingMode.HALF_UP));

        BigDecimal currentPrice = priceInfo.getCurrentPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        performanceDtoBuilder.currentPrice(currentPrice);

        BigDecimal currentMarketValue = holding.getQuantity().multiply(currentPrice);
        performanceDtoBuilder.currentMarketValue(currentMarketValue.setScale(MONEY_SCALE, RoundingMode.HALF_UP));

        BigDecimal dailyReturn = BigDecimal.ZERO;
        BigDecimal dailyReturnRate = BigDecimal.ZERO;

        if (priceInfo.getPreviousPrice() != null && priceInfo.getPreviousPrice().compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal previousPrice = priceInfo.getPreviousPrice(); // No need to scale here if it's already a price
            dailyReturn = (currentPrice.subtract(previousPrice)).multiply(holding.getQuantity());
            dailyReturnRate = currentPrice.subtract(previousPrice)
                                .divide(previousPrice, DEFAULT_SCALE, RoundingMode.HALF_UP);
        } else {
            logger.warn("Previous price is null or zero for asset: {}, cannot calculate daily return/rate accurately. Asset current price: {}", holding.getAssetIdentifier(), currentPrice);
            // If current value exists but previous was zero (e.g. new asset), daily return is essentially the current market value against zero.
            // However, daily return rate is problematic. For now, keeping them zero as per original logic for this case.
        }
        performanceDtoBuilder.dailyReturn(dailyReturn.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        performanceDtoBuilder.dailyReturnRate(dailyReturnRate); // Already scaled if calculated

        BigDecimal totalProfitAndLoss = currentMarketValue.subtract(initialCost);
        performanceDtoBuilder.totalProfitAndLoss(totalProfitAndLoss.setScale(MONEY_SCALE, RoundingMode.HALF_UP));

        BigDecimal totalProfitAndLossRate = BigDecimal.ZERO;
        if (initialCost.compareTo(BigDecimal.ZERO) != 0) {
            totalProfitAndLossRate = totalProfitAndLoss.divide(initialCost, DEFAULT_SCALE, RoundingMode.HALF_UP);
        }
        performanceDtoBuilder.totalProfitAndLossRate(totalProfitAndLossRate);

        return performanceDtoBuilder.build();
    }

    @Override
    public BigDecimal calculatePortfolioInitialCost(List<PortfolioHolding> holdings) {
        if (holdings == null || holdings.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal totalInitialCost = BigDecimal.ZERO;
        for (PortfolioHolding holding : holdings) {
            if (holding.getQuantity() != null && holding.getAverageCostPrice() != null) {
                totalInitialCost = totalInitialCost.add(holding.getQuantity().multiply(holding.getAverageCostPrice()));
            }
        }
        return totalInitialCost.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    @Override
    public BigDecimal calculatePortfolioMarketValue(List<PortfolioHolding> holdings, Map<String, AssetPriceInfo> assetPrices) {
        if (holdings == null || holdings.isEmpty() || assetPrices == null || assetPrices.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal totalMarketValue = BigDecimal.ZERO;
        for (PortfolioHolding holding : holdings) {
            AssetPriceInfo priceInfo = assetPrices.get(holding.getAssetIdentifier());
            if (priceInfo != null && priceInfo.getCurrentPrice() != null && holding.getQuantity() != null) {
                totalMarketValue = totalMarketValue.add(holding.getQuantity().multiply(priceInfo.getCurrentPrice()));
            } else {
                logger.warn("Missing current price or quantity for asset {} while calculating total market value.", holding.getAssetIdentifier());
            }
        }
        return totalMarketValue.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}

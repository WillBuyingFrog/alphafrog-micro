package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.PortfolioHolding;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.HoldingPerformanceDto;
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

    /**
     * 计算单个持仓的表现数据
     * <p>该方法根据持仓信息、资产信息和价格信息，计算并返回持仓的表现数据，包括：</p>
     * <ul>
     *   <li>资产基本信息（标识符、类型、名称、数量）</li>
     *   <li>平均成本价（保留2位小数）</li>
     *   <li>初始成本（数量 * 平均成本价，保留2位小数）</li>
     *   <li>当前价格（保留2位小数）</li>
     *   <li>当前市值（数量 * 当前价格，保留2位小数）</li>
     *   <li>日收益（保留2位小数）</li>
     *   <li>日收益率（保留4位小数）</li>
     *   <li>总盈亏（保留2位小数）</li>
     *   <li>总盈亏率（保留4位小数）</li>
     * </ul>
     * 
     * @param holding 持仓信息，包含资产标识符、类型、数量、平均成本价等
     * @param assetInfo 资产信息，包含资产名称等
     * @param priceInfo 价格信息，包含当前价格、前一日价格等
     * @return 包含所有计算结果的HoldingPerformanceDto对象，如果输入参数不完整则返回null
     * @throws NullPointerException 如果输入参数为null
     */
    @Override
    public HoldingPerformanceDto calculateHoldingPerformance(PortfolioHolding holding, AssetInfo assetInfo, AssetPriceInfo priceInfo) {
        // 检查输入参数是否完整
        if (holding == null || assetInfo == null || priceInfo == null || priceInfo.getCurrentPrice() == null || holding.getQuantity() == null || holding.getAverageCostPrice() == null) {
            logger.warn("Missing data for holding performance calculation: holdingId={}, assetIdentifier={}, assetInfo={}, priceInfo={}",
                        holding != null ? holding.getId() : "null",
                        holding != null ? holding.getAssetIdentifier() : "null",
                        assetInfo, priceInfo);
            return null;
        }

        // 初始化组合表现DTO构建器
        HoldingPerformanceDto.HoldingPerformanceDtoBuilder performanceDtoBuilder = HoldingPerformanceDto.builder();
        // 设置资产基本信息
        performanceDtoBuilder.assetIdentifier(holding.getAssetIdentifier());
        performanceDtoBuilder.assetType(holding.getAssetType());
        performanceDtoBuilder.assetName(assetInfo.getAssetName());
        performanceDtoBuilder.quantity(holding.getQuantity());

        // 计算并设置平均成本价
        BigDecimal averageCostPrice = holding.getAverageCostPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        performanceDtoBuilder.averageCostPrice(averageCostPrice);

        // 计算并设置初始成本
        BigDecimal initialCost = holding.getQuantity().multiply(averageCostPrice);
        performanceDtoBuilder.initialCost(initialCost.setScale(MONEY_SCALE, RoundingMode.HALF_UP));

        // 获取并设置当前价格
        BigDecimal currentPrice = priceInfo.getCurrentPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        performanceDtoBuilder.currentPrice(currentPrice);

        // 计算并设置当前市值
        BigDecimal currentMarketValue = holding.getQuantity().multiply(currentPrice);
        performanceDtoBuilder.currentMarketValue(currentMarketValue.setScale(MONEY_SCALE, RoundingMode.HALF_UP));

        // 初始化日收益和日收益率
        BigDecimal dailyReturn = BigDecimal.ZERO;
        BigDecimal dailyReturnRate = BigDecimal.ZERO;

        // 如果有前一日价格，计算日收益和日收益率
        if (priceInfo.getPreviousPrice() != null && priceInfo.getPreviousPrice().compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal previousPrice = priceInfo.getPreviousPrice(); // 前一日价格
            dailyReturn = (currentPrice.subtract(previousPrice)).multiply(holding.getQuantity());
            dailyReturnRate = currentPrice.subtract(previousPrice)
                                .divide(previousPrice, DEFAULT_SCALE, RoundingMode.HALF_UP);
        } else {
            logger.warn("Previous price is null or zero for asset: {}, cannot calculate daily return/rate accurately. Asset current price: {}", holding.getAssetIdentifier(), currentPrice);
        }
        performanceDtoBuilder.dailyReturn(dailyReturn.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        performanceDtoBuilder.dailyReturnRate(dailyReturnRate);

        // 计算并设置总盈亏
        BigDecimal totalProfitAndLoss = currentMarketValue.subtract(initialCost);
        performanceDtoBuilder.totalProfitAndLoss(totalProfitAndLoss.setScale(MONEY_SCALE, RoundingMode.HALF_UP));

        // 计算并设置总盈亏率
        BigDecimal totalProfitAndLossRate = BigDecimal.ZERO;
        if (initialCost.compareTo(BigDecimal.ZERO) != 0) {
            totalProfitAndLossRate = totalProfitAndLoss.divide(initialCost, DEFAULT_SCALE, RoundingMode.HALF_UP);
        }
        performanceDtoBuilder.totalProfitAndLossRate(totalProfitAndLossRate);

        return performanceDtoBuilder.build();
    }

    /**
     * 计算投资组合的总初始成本
     * <p>该方法遍历所有持仓，计算每个持仓的初始成本（数量 * 平均成本价），并将所有持仓的初始成本相加得到总初始成本。</p>
     * <p>如果某个持仓缺少数量或平均成本价信息，则该持仓不会被计入总成本。</p>
     * 
     * @param holdings 持仓列表，包含每个持仓的数量和平均成本价信息
     * @return 投资组合的总初始成本（保留2位小数），如果持仓列表为空则返回0
     */
    @Override
    public BigDecimal calculatePortfolioInitialCost(List<PortfolioHolding> holdings) {
        // 如果持仓列表为空，返回0
        if (holdings == null || holdings.isEmpty()) {
            return BigDecimal.ZERO;
        }
        // 计算组合总初始成本
        BigDecimal totalInitialCost = BigDecimal.ZERO;
        for (PortfolioHolding holding : holdings) {
            if (holding.getQuantity() != null && holding.getAverageCostPrice() != null) {
                totalInitialCost = totalInitialCost.add(holding.getQuantity().multiply(holding.getAverageCostPrice()));
            }
        }
        return totalInitialCost.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算投资组合的总市值
     * <p>该方法遍历所有持仓，根据资产价格信息计算每个持仓的当前市值（数量 * 当前价格），并将所有持仓的市值相加得到总市值。</p>
     * <p>如果某个持仓缺少价格信息或数量信息，则该持仓不会被计入总市值。</p>
     * 
     * @param holdings 持仓列表，包含每个持仓的资产标识符和数量信息
     * @param assetPrices 资产价格信息映射表，key为资产标识符，value为包含当前价格的价格信息
     * @return 投资组合的总市值（保留2位小数），如果持仓列表或价格信息为空则返回0
     */
    @Override
    public BigDecimal calculatePortfolioMarketValue(List<PortfolioHolding> holdings, Map<String, AssetPriceInfo> assetPrices) {
        // 如果持仓列表或价格信息为空，返回0
        if (holdings == null || holdings.isEmpty() || assetPrices == null || assetPrices.isEmpty()) {
            return BigDecimal.ZERO;
        }
        // 计算组合总市值
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

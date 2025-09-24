package world.willfrog.alphafrogmicro.portfolioservice.service;

import world.willfrog.alphafrogmicro.common.pojo.portfolio.PortfolioHolding;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.HoldingPerformanceDto;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetInfo;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetPriceInfo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface PortfolioCalculatorService {

    /**
     * 计算单个持仓的表现详情。
     *
     * @param holding 持仓信息
     * @param assetInfo 资产基本信息 (包含名称)
     * @param priceInfo 资产价格信息 (当日价格、昨日价格)
     * @return 单个持仓的表现DTO
     */
    HoldingPerformanceDto calculateHoldingPerformance(PortfolioHolding holding, AssetInfo assetInfo, AssetPriceInfo priceInfo);

    /**
     * 计算组合的初始总成本。
     *
     * @param holdings 组合中的所有持仓
     * @return 初始总成本
     */
    BigDecimal calculatePortfolioInitialCost(List<PortfolioHolding> holdings);

    /**
     * 计算组合的某日总市值。
     *
     * @param holdings 组合中的所有持仓
     * @param assetPrices 当日各资产价格信息 Map (key: assetIdentifier)
     * @return 总市值
     */
    BigDecimal calculatePortfolioMarketValue(List<PortfolioHolding> holdings, Map<String, AssetPriceInfo> assetPrices);

} 
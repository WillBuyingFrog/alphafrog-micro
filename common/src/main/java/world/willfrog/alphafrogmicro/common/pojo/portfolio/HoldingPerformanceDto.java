package world.willfrog.alphafrogmicro.common.pojo.portfolio;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Data Transfer Object representing the performance of a single holding within a portfolio.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldingPerformanceDto {

    /**
     * Unique identifier for the asset (e.g., stock ticker, fund code).
     */
    private String assetIdentifier;

    /**
     * Name of the asset.
     */
    private String assetName;

    /**
     * Type of the asset (e.g., STOCK, FUND_ETF).
     */
    private AssetType assetType;

    /**
     * Quantity of the asset held.
     */
    private BigDecimal quantity;

    /**
     * Average cost price per unit of the asset.
     */
    private BigDecimal averageCostPrice;

    /**
     * Total initial cost of the holding (quantity * averageCostPrice).
     */
    private BigDecimal initialCost;

    /**
     * Current market price per unit of the asset.
     */
    private BigDecimal currentPrice;

    /**
     * Current total market value of the holding (quantity * currentPrice).
     */
    private BigDecimal currentMarketValue;

    /**
     * Monetary gain or loss for the current day.
     */
    private BigDecimal dailyReturn;

    /**
     * Percentage gain or loss for the current day.
     * This is used to rank top gainers and losers.
     */
    private BigDecimal dailyReturnRate;

    /**
     * Total profit or loss since the holding was acquired (currentMarketValue - initialCost).
     * Corresponds to 'profitAndLossContribution' from the overview.
     */
    private BigDecimal totalProfitAndLoss;

    /**
     * Total profit or loss rate since the holding was acquired (totalProfitAndLoss / initialCost).
     */
    private BigDecimal totalProfitAndLossRate;

} 
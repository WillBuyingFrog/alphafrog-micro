package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.AssetType;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.HoldingPerformanceDto;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.PortfolioHolding;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetInfo;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetPriceInfo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("it")
class PortfolioCalculatorServiceImplIT {

    @Autowired
    private PortfolioCalculatorServiceImpl portfolioCalculatorService;

    private final int MONEY_SCALE = 2;
    private final int DEFAULT_SCALE = 4;

    @Test
    void calculateHoldingPerformance_withCompleteData_shouldReturnCorrectPerformance() {
        // Arrange
        PortfolioHolding holding = new PortfolioHolding();
        holding.setId(1L);
        holding.setAssetIdentifier("AAPL");
        holding.setAssetType(AssetType.STOCK);
        holding.setQuantity(new BigDecimal("100"));
        holding.setAverageCostPrice(new BigDecimal("150.00"));

        AssetInfo assetInfo = new AssetInfo("AAPL", AssetType.STOCK, "Apple Inc.");

        AssetPriceInfo priceInfo = new AssetPriceInfo(
            "AAPL",
            new BigDecimal("160.00"),
            new BigDecimal("158.00")
        );

        // Act
        HoldingPerformanceDto performance = portfolioCalculatorService.calculateHoldingPerformance(holding, assetInfo, priceInfo);

        // Assert
        assertThat(performance).isNotNull();
        assertThat(performance.getAssetIdentifier()).isEqualTo("AAPL");
        assertThat(performance.getAssetType()).isEqualTo(AssetType.STOCK);
        assertThat(performance.getAssetName()).isEqualTo("Apple Inc.");
        assertThat(performance.getQuantity()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(performance.getAverageCostPrice()).isEqualByComparingTo(new BigDecimal("150.00").setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        assertThat(performance.getInitialCost()).isEqualByComparingTo(new BigDecimal("15000.00").setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        assertThat(performance.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("160.00").setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        assertThat(performance.getCurrentMarketValue()).isEqualByComparingTo(new BigDecimal("16000.00").setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        assertThat(performance.getDailyReturn()).isEqualByComparingTo(new BigDecimal("200.00").setScale(MONEY_SCALE, RoundingMode.HALF_UP)); // (160-158)*100
        assertThat(performance.getDailyReturnRate()).isEqualByComparingTo(new BigDecimal("0.0127").setScale(DEFAULT_SCALE, RoundingMode.HALF_UP)); // (160-158)/158
        assertThat(performance.getTotalProfitAndLoss()).isEqualByComparingTo(new BigDecimal("1000.00").setScale(MONEY_SCALE, RoundingMode.HALF_UP)); // 16000-15000
        assertThat(performance.getTotalProfitAndLossRate()).isEqualByComparingTo(new BigDecimal("0.0667").setScale(DEFAULT_SCALE, RoundingMode.HALF_UP)); // 1000/15000 rounded from 0.066666...
    }

    @Test
    void calculateHoldingPerformance_withMissingHoldingQuantity_shouldReturnNull() {
        // Arrange
        PortfolioHolding holding = new PortfolioHolding();
        holding.setId(1L);
        holding.setAssetIdentifier("AAPL");
        holding.setAssetType(AssetType.STOCK);
        // holding.setQuantity(new BigDecimal("100")); // Missing quantity
        holding.setAverageCostPrice(new BigDecimal("150.00"));

        AssetInfo assetInfo = new AssetInfo("AAPL", AssetType.STOCK, "Apple Inc.");
        AssetPriceInfo priceInfo = new AssetPriceInfo("AAPL", new BigDecimal("160.00"), new BigDecimal("158.00"));

        // Act
        HoldingPerformanceDto performance = portfolioCalculatorService.calculateHoldingPerformance(holding, assetInfo, priceInfo);

        // Assert
        assertThat(performance).isNull();
    }
    
    @Test
    void calculateHoldingPerformance_withNullAssetInfo_shouldReturnNull() {
        // Arrange
        PortfolioHolding holding = new PortfolioHolding();
        holding.setId(1L);
        holding.setAssetIdentifier("AAPL");
        holding.setAssetType(AssetType.STOCK);
        holding.setQuantity(new BigDecimal("100"));
        holding.setAverageCostPrice(new BigDecimal("150.00"));

        AssetPriceInfo priceInfo = new AssetPriceInfo("AAPL", new BigDecimal("160.00"), new BigDecimal("158.00"));

        // Act
        HoldingPerformanceDto performance = portfolioCalculatorService.calculateHoldingPerformance(holding, null, priceInfo);

        // Assert
        assertThat(performance).isNull();
    }

    @Test
    void calculateHoldingPerformance_withNoPreviousPrice_shouldCalculateWithoutDailyReturn() {
        // Arrange
        PortfolioHolding holding = new PortfolioHolding();
        holding.setAssetIdentifier("TSLA");
        holding.setAssetType(AssetType.STOCK);
        holding.setQuantity(new BigDecimal("50"));
        holding.setAverageCostPrice(new BigDecimal("200.00"));

        AssetInfo assetInfo = new AssetInfo("TSLA", AssetType.STOCK, "Tesla Inc.");

        AssetPriceInfo priceInfo = new AssetPriceInfo(
            "TSLA",
            new BigDecimal("250.00"),
            null // No previous price
        );

        // Act
        HoldingPerformanceDto performance = portfolioCalculatorService.calculateHoldingPerformance(holding, assetInfo, priceInfo);

        // 测试当没有前一日价格时，计算持仓表现是否正确
        assertThat(performance).isNotNull();
        assertThat(performance.getCurrentMarketValue()).isEqualByComparingTo(new BigDecimal("12500.00").setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        assertThat(performance.getDailyReturn()).isEqualByComparingTo(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        assertThat(performance.getDailyReturnRate()).isEqualByComparingTo(BigDecimal.ZERO.setScale(DEFAULT_SCALE, RoundingMode.HALF_UP));
        assertThat(performance.getTotalProfitAndLoss()).isEqualByComparingTo(new BigDecimal("2500.00").setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        assertThat(performance.getTotalProfitAndLossRate()).isEqualByComparingTo(new BigDecimal("0.2500").setScale(DEFAULT_SCALE, RoundingMode.HALF_UP));
    }
    
    @Test
    void calculateHoldingPerformance_withZeroPreviousPrice_shouldCalculateWithoutDailyReturn() {
        // Arrange
        PortfolioHolding holding = new PortfolioHolding();
        holding.setAssetIdentifier("AMZN");
        holding.setAssetType(AssetType.STOCK);
        holding.setQuantity(new BigDecimal("10"));
        holding.setAverageCostPrice(new BigDecimal("100.00"));

        AssetInfo assetInfo = new AssetInfo("AMZN", AssetType.STOCK, "Amazon.com Inc.");

        AssetPriceInfo priceInfo = new AssetPriceInfo(
            "AMZN",
            new BigDecimal("110.00"),
            BigDecimal.ZERO // Zero previous price
        );
         // Act
        HoldingPerformanceDto performance = portfolioCalculatorService.calculateHoldingPerformance(holding, assetInfo, priceInfo);

        // Assert
        assertThat(performance).isNotNull();
        assertThat(performance.getDailyReturn()).isEqualByComparingTo(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        assertThat(performance.getDailyReturnRate()).isEqualByComparingTo(BigDecimal.ZERO.setScale(DEFAULT_SCALE, RoundingMode.HALF_UP));
        assertThat(performance.getTotalProfitAndLoss()).isEqualByComparingTo(new BigDecimal("100.00").setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        assertThat(performance.getTotalProfitAndLossRate()).isEqualByComparingTo(new BigDecimal("0.1000").setScale(DEFAULT_SCALE, RoundingMode.HALF_UP));
    }
    
    @Test
    void calculateHoldingPerformance_withZeroInitialCost_shouldHaveZeroTotalProfitAndLossRate() {
        // Arrange
        PortfolioHolding holding = new PortfolioHolding();
        holding.setAssetIdentifier("GOOG");
        holding.setAssetType(AssetType.STOCK);
        holding.setQuantity(new BigDecimal("5"));
        holding.setAverageCostPrice(BigDecimal.ZERO); // Zero initial cost

        AssetInfo assetInfo = new AssetInfo("GOOG", AssetType.STOCK, "Alphabet Inc.");
        AssetPriceInfo priceInfo = new AssetPriceInfo("GOOG", new BigDecimal("100.00"), new BigDecimal("98.00"));

        // Act
        HoldingPerformanceDto performance = portfolioCalculatorService.calculateHoldingPerformance(holding, assetInfo, priceInfo);

        // Assert
        assertThat(performance).isNotNull();
        assertThat(performance.getInitialCost()).isEqualByComparingTo(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        assertThat(performance.getTotalProfitAndLoss()).isEqualByComparingTo(new BigDecimal("500.00").setScale(MONEY_SCALE, RoundingMode.HALF_UP)); // 5 * 100
        assertThat(performance.getTotalProfitAndLossRate()).isEqualByComparingTo(BigDecimal.ZERO.setScale(DEFAULT_SCALE, RoundingMode.HALF_UP));
    }

    @Test
    void calculatePortfolioInitialCost_withMultipleHoldings_shouldReturnCorrectTotal() {
        // Arrange
        PortfolioHolding holding1 = new PortfolioHolding();
        holding1.setQuantity(new BigDecimal("100"));
        holding1.setAverageCostPrice(new BigDecimal("150.00"));

        PortfolioHolding holding2 = new PortfolioHolding();
        holding2.setQuantity(new BigDecimal("50"));
        holding2.setAverageCostPrice(new BigDecimal("200.00"));

        PortfolioHolding holding3 = new PortfolioHolding();
        holding3.setQuantity(new BigDecimal("200"));
        holding3.setAverageCostPrice(new BigDecimal("25.50"));

        List<PortfolioHolding> holdings = Arrays.asList(holding1, holding2, holding3);

        // Act
        BigDecimal totalInitialCost = portfolioCalculatorService.calculatePortfolioInitialCost(holdings);

        // Assert
        // 100*150 + 50*200 + 200*25.50 = 15000 + 10000 + 5100 = 30100
        assertThat(totalInitialCost).isEqualByComparingTo(new BigDecimal("30100.00").setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    @Test
    void calculatePortfolioInitialCost_withEmptyHoldings_shouldReturnZero() {
        // Arrange
        List<PortfolioHolding> holdings = Collections.emptyList();

        // Act
        BigDecimal totalInitialCost = portfolioCalculatorService.calculatePortfolioInitialCost(holdings);

        // Assert
        assertThat(totalInitialCost).isEqualByComparingTo(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    @Test
    void calculatePortfolioInitialCost_withNullHoldings_shouldReturnZero() {
        // Act
        BigDecimal totalInitialCost = portfolioCalculatorService.calculatePortfolioInitialCost(null);

        // Assert
        assertThat(totalInitialCost).isEqualByComparingTo(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    @Test
    void calculatePortfolioInitialCost_withIncompleteData_shouldSkipIncompleteHoldings() {
        // Arrange
        PortfolioHolding holding1 = new PortfolioHolding();
        holding1.setQuantity(new BigDecimal("100"));
        holding1.setAverageCostPrice(new BigDecimal("150.00"));

        PortfolioHolding holding2 = new PortfolioHolding();
        holding2.setQuantity(new BigDecimal("50"));
        // Missing average cost price for holding2

        PortfolioHolding holding3 = new PortfolioHolding();
        // Missing both quantity and average cost price for holding3
        holding3.setAverageCostPrice(new BigDecimal("20.00"));
        
        PortfolioHolding holding4 = new PortfolioHolding();
        // Missing quantity for holding4
        holding4.setAverageCostPrice(new BigDecimal("30.00"));


        List<PortfolioHolding> holdings = Arrays.asList(holding1, holding2, holding3, holding4);

        // Act
        BigDecimal totalInitialCost = portfolioCalculatorService.calculatePortfolioInitialCost(holdings);

        // Assert
        // Only holding1 should be counted: 100*150 = 15000
        assertThat(totalInitialCost).isEqualByComparingTo(new BigDecimal("15000.00").setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    @Test
    void calculatePortfolioMarketValue_withCompleteData_shouldReturnCorrectTotal() {
        // Arrange
        PortfolioHolding holding1 = new PortfolioHolding();
        holding1.setAssetIdentifier("AAPL");
        holding1.setQuantity(new BigDecimal("100"));

        PortfolioHolding holding2 = new PortfolioHolding();
        holding2.setAssetIdentifier("TSLA");
        holding2.setQuantity(new BigDecimal("50"));

        PortfolioHolding holding3 = new PortfolioHolding();
        holding3.setAssetIdentifier("MSFT");
        holding3.setQuantity(new BigDecimal("75"));

        List<PortfolioHolding> holdings = Arrays.asList(holding1, holding2, holding3);

        Map<String, AssetPriceInfo> assetPrices = new HashMap<>();
        
        assetPrices.put("AAPL", new AssetPriceInfo("AAPL", new BigDecimal("160.00"), null));
        assetPrices.put("TSLA", new AssetPriceInfo("TSLA", new BigDecimal("250.00"), null));
        assetPrices.put("MSFT", new AssetPriceInfo("MSFT", new BigDecimal("300.00"), null));

        // Act
        BigDecimal totalMarketValue = portfolioCalculatorService.calculatePortfolioMarketValue(holdings, assetPrices);

        // Assert
        // 100*160 + 50*250 + 75*300 = 16000 + 12500 + 22500 = 51000
        assertThat(totalMarketValue).isEqualByComparingTo(new BigDecimal("51000.00").setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    @Test
    void calculatePortfolioMarketValue_withMissingPrices_shouldSkipAssetsWithoutPrices() {
        // Arrange
        PortfolioHolding holding1 = new PortfolioHolding();
        holding1.setAssetIdentifier("AAPL");
        holding1.setQuantity(new BigDecimal("100"));

        PortfolioHolding holding2 = new PortfolioHolding();
        holding2.setAssetIdentifier("TSLA"); // Price for TSLA will be missing
        holding2.setQuantity(new BigDecimal("50"));

        List<PortfolioHolding> holdings = Arrays.asList(holding1, holding2);

        Map<String, AssetPriceInfo> assetPrices = new HashMap<>();
        assetPrices.put("AAPL", new AssetPriceInfo("AAPL", new BigDecimal("160.00"), null));
        // TSLA price is missing from the map

        // Act
        BigDecimal totalMarketValue = portfolioCalculatorService.calculatePortfolioMarketValue(holdings, assetPrices);

        // Assert
        // Only AAPL should be counted: 100*160 = 16000
        assertThat(totalMarketValue).isEqualByComparingTo(new BigDecimal("16000.00").setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }
    
    @Test
    void calculatePortfolioMarketValue_withHoldingMissingQuantity_shouldSkipHolding() {
        // Arrange
        PortfolioHolding holding1 = new PortfolioHolding();
        holding1.setAssetIdentifier("AAPL");
        holding1.setQuantity(new BigDecimal("100"));

        PortfolioHolding holding2 = new PortfolioHolding();
        holding2.setAssetIdentifier("TSLA");
        // holding2.setQuantity(new BigDecimal("50")); // Missing quantity for TSLA

        List<PortfolioHolding> holdings = Arrays.asList(holding1, holding2);
        Map<String, AssetPriceInfo> assetPrices = new HashMap<>();
        assetPrices.put("AAPL", new AssetPriceInfo("AAPL", new BigDecimal("160.00"), null));
        assetPrices.put("TSLA", new AssetPriceInfo("TSLA", new BigDecimal("250.00"), null));
        
        // Act
        BigDecimal totalMarketValue = portfolioCalculatorService.calculatePortfolioMarketValue(holdings, assetPrices);

        // Assert
        // Only AAPL should be counted: 100*160 = 16000
        assertThat(totalMarketValue).isEqualByComparingTo(new BigDecimal("16000.00").setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    @Test
    void calculatePortfolioMarketValue_withEmptyHoldings_shouldReturnZero() {
        // Arrange
        List<PortfolioHolding> holdings = Collections.emptyList();
        Map<String, AssetPriceInfo> assetPrices = new HashMap<>();

        // Act
        BigDecimal totalMarketValue = portfolioCalculatorService.calculatePortfolioMarketValue(holdings, assetPrices);

        // Assert
        assertThat(totalMarketValue).isEqualByComparingTo(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    @Test
    void calculatePortfolioMarketValue_withNullInputs_shouldReturnZero() {
        // Act & Assert
        assertThat(portfolioCalculatorService.calculatePortfolioMarketValue(null, new HashMap<>()))
            .isEqualByComparingTo(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        
        assertThat(portfolioCalculatorService.calculatePortfolioMarketValue(Collections.emptyList(), null))
            .isEqualByComparingTo(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        
        assertThat(portfolioCalculatorService.calculatePortfolioMarketValue(null, null))
            .isEqualByComparingTo(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }
} 
package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundNavDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockQuoteDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundNav;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockDaily;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.AssetType;
import world.willfrog.alphafrogmicro.common.service.CommonCalendarService;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetPriceInfo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AssetPriceServiceImplTest {

    @Mock
    private StockQuoteDao stockQuoteDao;
    @Mock
    private FundNavDao fundNavDao;
    @Mock
    private CommonCalendarService commonCalendarService;

    @InjectMocks
    private AssetPriceServiceImpl assetPriceService;

    private LocalDate today;
    private LocalDate yesterday;
    private long todayTimestamp;
    private long yesterdayTimestamp;

    @BeforeEach
    void setUp() {
        today = LocalDate.of(2023, 10, 27);
        yesterday = LocalDate.of(2023, 10, 26);
        todayTimestamp = DateConvertUtils.convertLocalDateToMsTimestamp(today);
        yesterdayTimestamp = DateConvertUtils.convertLocalDateToMsTimestamp(yesterday);

        // Mock commonCalendarService to always return 'yesterday' as previous trading day
        when(commonCalendarService.getPreviousTradingDate(eq(today), anyString())).thenReturn(yesterday);
    }

    @Test
    void getAssetPriceInfo_forStock_whenDataExists_shouldReturnPriceInfo() {
        // Arrange
        StockDaily prevDayStock = new StockDaily();
        prevDayStock.setClose(150.0);
        StockDaily todayStock = new StockDaily();
        todayStock.setClose(152.5);
        List<StockDaily> stockPrices = Arrays.asList(prevDayStock, todayStock);
        when(stockQuoteDao.getStockDailyByTsCodeAndDateRange("AAPL", yesterdayTimestamp, todayTimestamp))
            .thenReturn(stockPrices);

        // Act
        AssetPriceInfo priceInfo = assetPriceService.getAssetPriceInfo("AAPL", AssetType.STOCK, today);

        // Assert
        assertThat(priceInfo).isNotNull();
        assertThat(priceInfo.getAssetIdentifier()).isEqualTo("AAPL");
        assertThat(priceInfo.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("152.5"));
        assertThat(priceInfo.getPreviousPrice()).isEqualByComparingTo(new BigDecimal("150.0"));
    }

    @Test
    void getAssetPriceInfo_forStock_whenNotEnoughData_shouldReturnNull() {
        // Arrange
        StockDaily todayStock = new StockDaily(); // Only one day's data
        todayStock.setClose(152.5);
        when(stockQuoteDao.getStockDailyByTsCodeAndDateRange("AAPL", yesterdayTimestamp, todayTimestamp))
            .thenReturn(Collections.singletonList(todayStock));

        // Act
        AssetPriceInfo priceInfo = assetPriceService.getAssetPriceInfo("AAPL", AssetType.STOCK, today);

        // Assert
        assertThat(priceInfo).isNull(); // Current implementation expects 2 records
    }
    
    @Test
    void getAssetPriceInfo_forStock_whenCurrentPriceMissing_shouldReturnNull() {
        // Arrange
         StockDaily prevDayStock = new StockDaily();
        prevDayStock.setClose(150.0);
        // Missing today's stock data by returning only one record (or an empty list)
        when(stockQuoteDao.getStockDailyByTsCodeAndDateRange("AAPL", yesterdayTimestamp, todayTimestamp))
            .thenReturn(Collections.singletonList(prevDayStock)); // Simulates current price not found

        // Act
        AssetPriceInfo priceInfo = assetPriceService.getAssetPriceInfo("AAPL", AssetType.STOCK, today);

        // Assert
        assertThat(priceInfo).isNull(); 
    }

    @Test
    void getAssetPriceInfo_forFund_whenDataExists_shouldReturnPriceInfo() {
        // Arrange
        FundNav prevDayNav = new FundNav();
        prevDayNav.setUnitNav(10.0);
        FundNav todayNav = new FundNav();
        todayNav.setUnitNav(10.5);
        List<FundNav> fundNavs = Arrays.asList(prevDayNav, todayNav);
        when(fundNavDao.getFundNavsByTsCodeAndDateRange("FUND01", yesterdayTimestamp, todayTimestamp))
            .thenReturn(fundNavs);

        // Act
        AssetPriceInfo priceInfo = assetPriceService.getAssetPriceInfo("FUND01", AssetType.FUND_ETF, today);

        // Assert
        assertThat(priceInfo).isNotNull();
        assertThat(priceInfo.getAssetIdentifier()).isEqualTo("FUND01");
        assertThat(priceInfo.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("10.5"));
        assertThat(priceInfo.getPreviousPrice()).isEqualByComparingTo(new BigDecimal("10.0"));
    }

    @Test
    void getAssetPriceInfo_forUnsupportedType_shouldReturnNull() {
        // Act
        AssetPriceInfo priceInfo = assetPriceService.getAssetPriceInfo("UNKNOWN", null, today);
        // Assert
        assertThat(priceInfo).isNull();
    }

    @Test
    void getMultipleAssetPriceInfo_shouldReturnMapWithAvailablePrices() {
        // Arrange
        StockDaily prevDayStock = new StockDaily(); prevDayStock.setClose(150.0);
        StockDaily todayStock = new StockDaily(); todayStock.setClose(152.5);
        when(stockQuoteDao.getStockDailyByTsCodeAndDateRange("AAPL", yesterdayTimestamp, todayTimestamp))
            .thenReturn(Arrays.asList(prevDayStock, todayStock));

        FundNav prevDayNav = new FundNav(); prevDayNav.setUnitNav(10.0);
        FundNav todayNav = new FundNav(); todayNav.setUnitNav(10.5);
        when(fundNavDao.getFundNavsByTsCodeAndDateRange("FUND01", yesterdayTimestamp, todayTimestamp))
            .thenReturn(Arrays.asList(prevDayNav, todayNav));
        
        // Simulate a case where a third asset has no data
        when(stockQuoteDao.getStockDailyByTsCodeAndDateRange("GOOG", yesterdayTimestamp, todayTimestamp))
            .thenReturn(Collections.emptyList());

        Map<String, AssetType> assets = new HashMap<>();
        assets.put("AAPL", AssetType.STOCK);
        assets.put("FUND01", AssetType.FUND_ETF);
        assets.put("GOOG", AssetType.STOCK); // This one won't have price info

        // Act
        Map<String, AssetPriceInfo> prices = assetPriceService.getMultipleAssetPriceInfo(assets, today);

        // Assert
        assertThat(prices).isNotNull();
        assertThat(prices).hasSize(2); // Only AAPL and FUND01 should have entries
        assertThat(prices.get("AAPL").getCurrentPrice()).isEqualByComparingTo(new BigDecimal("152.5"));
        assertThat(prices.get("FUND01").getCurrentPrice()).isEqualByComparingTo(new BigDecimal("10.5"));
        assertThat(prices.containsKey("GOOG")).isFalse();
    }
    
    @Test
    void getAssetPriceInfo_whenPreviousTradingDateIsNull_shouldReturnNull() {
        // Arrange
        when(commonCalendarService.getPreviousTradingDate(eq(today), anyString())).thenReturn(null);

        // Act for STOCK
        AssetPriceInfo stockPriceInfo = assetPriceService.getAssetPriceInfo("AAPL", AssetType.STOCK, today);
        // Assert for STOCK
        assertThat(stockPriceInfo).isNull(); // Because previous trading date is needed to form the range

        // Act for FUND_ETF
        AssetPriceInfo fundPriceInfo = assetPriceService.getAssetPriceInfo("FUND01", AssetType.FUND_ETF, today);
        // Assert for FUND_ETF
        assertThat(fundPriceInfo).isNull();
    }
} 
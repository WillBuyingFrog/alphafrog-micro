package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundNavDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockQuoteDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundNav;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockDaily;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.AssetType;
import world.willfrog.alphafrogmicro.common.service.CommonCalendarService;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetPriceInfo;
import world.willfrog.alphafrogmicro.portfolioservice.service.AssetPriceService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("it")
@Transactional // Rollback database changes after each test (if DAOs are transactional)
class AssetPriceServiceImplIT {

    @Autowired
    private AssetPriceService assetPriceService;

    @Autowired
    private StockQuoteDao stockQuoteDao; // For inserting test data

    @Autowired
    private FundNavDao fundNavDao; // For inserting test data
    
    @Autowired
    private CommonCalendarService commonCalendarService; // Real bean, ensure it's configured for "it"

    private LocalDate date1; // e.g., 2023-10-27
    private LocalDate date2; // e.g., 2023-10-26
    private LocalDate date3; // e.g., 2023-10-25

    @BeforeEach
    void setUpTestData() {
        
        date1 = LocalDate.of(2023, 10, 27); // Friday
        date2 = LocalDate.of(2023, 10, 26); // Thursday
        date3 = LocalDate.of(2023, 10, 25); // Wednesday

        StockDaily aapl_d3 = new StockDaily(); 
        aapl_d3.setTsCode("AAPL_IT");
        aapl_d3.setTradeDate(DateConvertUtils.convertLocalDateToMsTimestamp(date3));
        aapl_d3.setClose(148.00);
        stockQuoteDao.insertStockDaily(aapl_d3); 

        StockDaily aapl_d2 = new StockDaily(); 
        aapl_d2.setTsCode("AAPL_IT");
        aapl_d2.setTradeDate(DateConvertUtils.convertLocalDateToMsTimestamp(date2));
        aapl_d2.setClose(150.00);
        stockQuoteDao.insertStockDaily(aapl_d2);

        StockDaily aapl_d1 = new StockDaily(); 
        aapl_d1.setTsCode("AAPL_IT");
        aapl_d1.setTradeDate(DateConvertUtils.convertLocalDateToMsTimestamp(date1));
        aapl_d1.setClose(152.50);
        stockQuoteDao.insertStockDaily(aapl_d1);

        FundNav fund01_d3 = new FundNav(); 
        fund01_d3.setTsCode("FUND01_IT");
        fund01_d3.setNavDate(DateConvertUtils.convertLocalDateToMsTimestamp(date3));
        fund01_d3.setUnitNav(9.8);
        fundNavDao.insertFundNav(fund01_d3); 

        FundNav fund01_d2 = new FundNav(); 
        fund01_d2.setTsCode("FUND01_IT");
        fund01_d2.setNavDate(DateConvertUtils.convertLocalDateToMsTimestamp(date2));
        fund01_d2.setUnitNav(10.0);
        fundNavDao.insertFundNav(fund01_d2);

        FundNav fund01_d1 = new FundNav(); 
        fund01_d1.setTsCode("FUND01_IT");
        fund01_d1.setNavDate(DateConvertUtils.convertLocalDateToMsTimestamp(date1));
        fund01_d1.setUnitNav(10.5);
        fundNavDao.insertFundNav(fund01_d1);
    }

    @Test
    void getAssetPriceInfo_forStock_whenDataExists_shouldReturnCorrectPrices() {
        AssetPriceInfo priceInfo = assetPriceService.getAssetPriceInfo("AAPL_IT", AssetType.STOCK, date1);

        assertThat(priceInfo).isNotNull();
        assertThat(priceInfo.getAssetIdentifier()).isEqualTo("AAPL_IT");
        assertThat(priceInfo.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("152.50"));
        assertThat(priceInfo.getPreviousPrice()).isEqualByComparingTo(new BigDecimal("150.00"));
    }

    @Test
    void getAssetPriceInfo_forFund_whenDataExists_shouldReturnCorrectPrices() {
        AssetPriceInfo priceInfo = assetPriceService.getAssetPriceInfo("FUND01_IT", AssetType.FUND_ETF, date1);

        assertThat(priceInfo).isNotNull();
        assertThat(priceInfo.getAssetIdentifier()).isEqualTo("FUND01_IT");
        assertThat(priceInfo.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("10.5"));
        assertThat(priceInfo.getPreviousPrice()).isEqualByComparingTo(new BigDecimal("10.0"));
    }

    @Test
    void getAssetPriceInfo_forStock_whenNoPriceForCurrentDate_shouldReturnNull() {
        StockDaily msft_d3 = new StockDaily(); 
        msft_d3.setTsCode("MSFT_IT");
        msft_d3.setTradeDate(DateConvertUtils.convertLocalDateToMsTimestamp(date3)); 
        msft_d3.setClose(290.00);
        stockQuoteDao.insertStockDaily(msft_d3);

        AssetPriceInfo priceInfo = assetPriceService.getAssetPriceInfo("MSFT_IT", AssetType.STOCK, date2); 
        assertThat(priceInfo).isNull();
    }
    
    @Test
    void getAssetPriceInfo_forStock_whenDataOnlyForPreviousDate_shouldStillReturnNullAsCurrentIsMissing() {
        StockDaily goog_d2 = new StockDaily(); 
        goog_d2.setTsCode("GOOG_IT");
        goog_d2.setTradeDate(DateConvertUtils.convertLocalDateToMsTimestamp(date2)); 
        goog_d2.setClose(120.00);
        stockQuoteDao.insertStockDaily(goog_d2);

        AssetPriceInfo priceInfo = assetPriceService.getAssetPriceInfo("GOOG_IT", AssetType.STOCK, date1);
        assertThat(priceInfo).isNull();
    }


    @Test
    void getAssetPriceInfo_whenNoPreviousTradingDayFound_shouldReturnNull() {
        LocalDate veryEarlyDate = LocalDate.of(1900, 1, 1);
        AssetPriceInfo priceInfo = assetPriceService.getAssetPriceInfo("AAPL_IT", AssetType.STOCK, veryEarlyDate);
        assertThat(priceInfo).isNull(); 
    }

    @Test
    void getMultipleAssetPriceInfo_shouldFetchAllAvailable() {
        Map<String, AssetType> assetsToFetch = new HashMap<>();
        assetsToFetch.put("AAPL_IT", AssetType.STOCK);
        assetsToFetch.put("FUND01_IT", AssetType.FUND_ETF);
        assetsToFetch.put("NONEXISTENT_STOCK_IT", AssetType.STOCK); 

        Map<String, AssetPriceInfo> prices = assetPriceService.getMultipleAssetPriceInfo(assetsToFetch, date1);

        assertThat(prices).isNotNull();
        assertThat(prices).hasSize(2); 
        assertThat(prices.containsKey("AAPL_IT")).isTrue();
        assertThat(prices.get("AAPL_IT").getCurrentPrice()).isEqualByComparingTo(new BigDecimal("152.50"));
        assertThat(prices.containsKey("FUND01_IT")).isTrue();
        assertThat(prices.get("FUND01_IT").getCurrentPrice()).isEqualByComparingTo(new BigDecimal("10.5"));
        assertThat(prices.containsKey("NONEXISTENT_STOCK_IT")).isFalse();
    }
} 
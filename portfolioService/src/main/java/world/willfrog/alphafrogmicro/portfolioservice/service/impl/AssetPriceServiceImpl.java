package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockQuoteDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundNavDao;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.AssetType;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetPriceInfo;
import world.willfrog.alphafrogmicro.portfolioservice.service.AssetPriceService;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.common.service.CommonCalendarService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AssetPriceServiceImpl implements AssetPriceService {

    private static final Logger logger = LoggerFactory.getLogger(AssetPriceServiceImpl.class);

    private final StockQuoteDao stockQuoteDao;
    private final FundNavDao fundNavDao;
    private final CommonCalendarService commonCalendarService;

    public AssetPriceServiceImpl(StockQuoteDao stockQuoteDao, FundNavDao fundNavDao, CommonCalendarService commonCalendarService) {
        this.stockQuoteDao = stockQuoteDao;
        this.fundNavDao = fundNavDao;
        this.commonCalendarService = commonCalendarService;
    }
    @Override
    public AssetPriceInfo getAssetPriceInfo(String assetIdentifier, AssetType assetType, LocalDate date) {
        logger.info("Fetching price for asset: {}, type: {}, date: {}", assetIdentifier, assetType, date);

        BigDecimal currentPrice = null;
        BigDecimal previousPrice = null;

        long currentDateTimestamp = DateConvertUtils.convertLocalDateToMsTimestamp(date);

        if (assetType == AssetType.STOCK) {
            // Fetch current day's stock price
            List<world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockDaily> currentDayStocks =
                    stockQuoteDao.getStockDailyByTsCodeAndDateRange(assetIdentifier, currentDateTimestamp, currentDateTimestamp);
            if (currentDayStocks != null && !currentDayStocks.isEmpty()) {
                currentPrice = BigDecimal.valueOf(currentDayStocks.get(0).getClose());  
                logger.debug("Found current stock price for {}: {} on {}", assetIdentifier, currentPrice, date);
            } else {
                logger.warn("Stock price not found for asset: {}, date: {}", assetIdentifier, date);
            }

            // Fetch previous trading day's stock price using CommonCalendarService
            // Assuming "SSE" for exchange. This might need to be dynamic based on assetIdentifier.
            LocalDate previousTradingDate = commonCalendarService.getPreviousTradingDate(date, "SSE"); 
            if (previousTradingDate != null) {
                long previousTradingDateTimestamp = DateConvertUtils.convertLocalDateToMsTimestamp(previousTradingDate);
                List<world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockDaily> prevDayStocks =
                        stockQuoteDao.getStockDailyByTsCodeAndDateRange(assetIdentifier, previousTradingDateTimestamp, previousTradingDateTimestamp);
                if (prevDayStocks != null && !prevDayStocks.isEmpty()) {
                    previousPrice = BigDecimal.valueOf(prevDayStocks.get(0).getClose());
                    logger.debug("Found previous stock price for {}: {} on {}", assetIdentifier, previousPrice, previousTradingDate);
                }
            }
            if (previousPrice == null) {
                logger.warn("Previous stock price not found for asset: {}, reference date: {}, derived previous trading date: {}",
                        assetIdentifier, date, previousTradingDate != null ? previousTradingDate.toString() : "N/A");
            }

        } else if (assetType == AssetType.FUND_ETF) {
            // Fetch current day's fund NAV
            List<world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundNav> currentDayFundNavs =
                    fundNavDao.getFundNavsByTsCodeAndDateRange(assetIdentifier, currentDateTimestamp, currentDateTimestamp);
            if (currentDayFundNavs != null && !currentDayFundNavs.isEmpty()) {
                currentPrice = BigDecimal.valueOf(currentDayFundNavs.get(0).getUnitNav());
                logger.debug("Found current fund NAV for {}: {} on {}", assetIdentifier, currentPrice, date);
            } else {
                logger.warn("Fund NAV not found for asset: {}, date: {}", assetIdentifier, date);
            }

            // Fetch previous trading day's fund NAV using CommonCalendarService
            // Assuming "SSE" for exchange. This might need to be dynamic based on assetIdentifier.
            LocalDate previousTradingDate = commonCalendarService.getPreviousTradingDate(date, "SSE");
            if (previousTradingDate != null) {
                long previousTradingDateTimestamp = DateConvertUtils.convertLocalDateToMsTimestamp(previousTradingDate);
                List<world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundNav> prevDayNavs =
                        fundNavDao.getFundNavsByTsCodeAndDateRange(assetIdentifier, previousTradingDateTimestamp, previousTradingDateTimestamp);
                if (prevDayNavs != null && !prevDayNavs.isEmpty()) {
                    previousPrice = BigDecimal.valueOf(prevDayNavs.get(0).getUnitNav());
                    logger.debug("Found previous fund NAV for {}: {} on {}", assetIdentifier, previousPrice, previousTradingDate);
                }
            }
            if (previousPrice == null) {
                logger.warn("Previous fund NAV not found for asset: {}, reference date: {}, derived previous trading date: {}",
                        assetIdentifier, date, previousTradingDate != null ? previousTradingDate.toString() : "N/A");
            }
        } else {
            logger.error("Unsupported asset type: {} for identifier: {}", assetType, assetIdentifier);
            return null; // Unsupported type
        }

        // Return AssetPriceInfo only if currentPrice is available, as it's essential.
        // Downstream services (like PortfolioCalculatorService) expect currentPrice.
        // previousPrice can be null and is handled downstream.
        if (currentPrice != null) {
            return new AssetPriceInfo(assetIdentifier, currentPrice, previousPrice);
        } else {
            logger.warn("Current price not found for asset: {}, type: {}, date: {}. Cannot create valid AssetPriceInfo.",
                    assetIdentifier, assetType, date);
            return null; // Indicate that essential data (current price for the specified date) is missing.
        }
    }

    @Override
    public Map<String, AssetPriceInfo> getMultipleAssetPriceInfo(Map<String, AssetType> assetIdentifiersWithTypes, LocalDate date) {
        Map<String, AssetPriceInfo> results = new HashMap<>();
        for (Map.Entry<String, AssetType> entry : assetIdentifiersWithTypes.entrySet()) {
            String assetIdentifier = entry.getKey();
            AssetType assetType = entry.getValue();
            AssetPriceInfo priceInfo = getAssetPriceInfo(assetIdentifier, assetType, date);
            if (priceInfo != null) {
                results.put(assetIdentifier, priceInfo);
            } else {
                // Handle case where price info might not be found for an asset
                logger.warn("Price information not found for asset: {}, type: {} on date: {}", assetIdentifier, assetType, date);
            }
        }
        return results;
    }
} 
package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import org.springframework.stereotype.Service;

import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockQuoteDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundNavDao;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.AssetType;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockDaily;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundNav;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetPriceInfo;
import world.willfrog.alphafrogmicro.portfolioservice.service.AssetPriceService;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.common.service.CommonCalendarService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class AssetPriceServiceImpl implements AssetPriceService {


    private final StockQuoteDao stockQuoteDao;
    private final FundNavDao fundNavDao;
    private final CommonCalendarService commonCalendarService;

    
    public AssetPriceServiceImpl(StockQuoteDao stockQuoteDao, FundNavDao fundNavDao, CommonCalendarService commonCalendarService) {
        this.stockQuoteDao = stockQuoteDao;
        this.fundNavDao = fundNavDao;
        this.commonCalendarService = commonCalendarService;
    }

    /**
     * 获取指定资产价格信息
     *
     * <p>该方法返回指定资产在查询日期及其前一交易日的价格信息。</p>
     * <p>返回的AssetPriceInfo对象包含以下内容：</p>
     * <ul>
     *   <li>当前价格 - 查询日期当天的收盘价</li>
     *   <li>前一交易日价格 - 查询日期前一交易日的收盘价</li>
     *   <li>价格变动 - 当前价格与前一交易日价格的差值</li>
     *   <li>价格变动百分比 - 价格变动的百分比值</li>
     * </ul>
     *
     * @param assetIdentifier 资产标识符(股票代码或基金代码)
     * @param assetType 资产类型（STOCK-股票 或 FUND_ETF-基金ETF）
     * @param date 查询日期
     * @return 包含当前价格和前一交易日价格的AssetPriceInfo对象
     * @throws IllegalArgumentException 如果输入参数无效(如assetIdentifier为空、assetType为null、date为null等)
     * @throws RuntimeException 如果数据访问或处理过程中发生错误(如数据库访问异常、日期转换异常等)
     * @see AssetPriceInfo
     * @see AssetType
     * @see LocalDate
     */

    @Override
    public AssetPriceInfo getAssetPriceInfo(String assetIdentifier, AssetType assetType, LocalDate date) {
        log.info("Fetching price for asset: {}, type: {}, date: {}", assetIdentifier, assetType, date);

        BigDecimal currentPrice = null;
        BigDecimal previousPrice = null;

        long currentDateTimestamp = DateConvertUtils.convertLocalDateToMsTimestamp(date);

        if (assetType == AssetType.STOCK) {
            // 获取上一个交易日
            LocalDate previousTradingDate = commonCalendarService.getPreviousTradingDate(date, "SSE");
            if (previousTradingDate != null) {
                long previousTradingDateTimestamp = DateConvertUtils.convertLocalDateToMsTimestamp(previousTradingDate);
                
                // 一次性查询从上一个交易日到当前日期的数据
                List<StockDaily> stockPrices = stockQuoteDao.getStockDailyByTsCodeAndDateRange(
                    assetIdentifier, previousTradingDateTimestamp, currentDateTimestamp);
                
                if (stockPrices != null && stockPrices.size() >= 2) {
                    // 直接取下标为1的元素作为最新价格，下标为0的元素作为前一个交易日价格
                    // 因为如果日期查询正确，那么返回的List就应该只有两个元素
                    currentPrice = BigDecimal.valueOf(stockPrices.get(1).getClose());
                    previousPrice = BigDecimal.valueOf(stockPrices.get(0).getClose());
                    
                    log.debug("Found current stock price for {}: {} on {}", assetIdentifier, currentPrice, date);
                    log.debug("Found previous stock price for {}: {} on {}", 
                        assetIdentifier, previousPrice, previousTradingDate);
                } else {
                    log.warn("Insufficient stock price data found for asset: {}, date range: {} to {}", 
                        assetIdentifier, previousTradingDate, date);
                }
            }
            
            if (previousPrice == null) {
                log.warn("Previous stock price not found for asset: {}, reference date: {}, derived previous trading date: {}",
                    assetIdentifier, date, previousTradingDate != null ? previousTradingDate.toString() : "N/A");
            }

        } else if (assetType == AssetType.FUND_ETF) {
            // 获取上一个交易日
            LocalDate previousTradingDate = commonCalendarService.getPreviousTradingDate(date, "SSE");
            if (previousTradingDate != null) {
                long previousTradingDateTimestamp = DateConvertUtils.convertLocalDateToMsTimestamp(previousTradingDate);
                
                // 一次性查询从上一个交易日到当前日期的基金净值数据
                List<FundNav> fundNavs = fundNavDao.getFundNavsByTsCodeAndDateRange(
                    assetIdentifier, previousTradingDateTimestamp, currentDateTimestamp);
                
                if (fundNavs != null && fundNavs.size() >= 2) {
                    // 直接取下标为1的元素作为最新净值，下标为0的元素作为前一个交易日净值
                    // 因为如果日期查询正确，那么返回的List就应该只有两个元素
                    currentPrice = BigDecimal.valueOf(fundNavs.get(1).getUnitNav());
                    previousPrice = BigDecimal.valueOf(fundNavs.get(0).getUnitNav());
                    
                    log.debug("Found current fund NAV for {}: {} on {}", assetIdentifier, currentPrice, date);
                    log.debug("Found previous fund NAV for {}: {} on {}", 
                        assetIdentifier, previousPrice, previousTradingDate);
                } else {
                    log.warn("Insufficient fund NAV data found for asset: {}, date range: {} to {}", 
                        assetIdentifier, previousTradingDate, date);
                }
            }
            
            if (previousPrice == null) {
                log.warn("Previous fund NAV not found for asset: {}, reference date: {}, derived previous trading date: {}",
                    assetIdentifier, date, previousTradingDate != null ? previousTradingDate.toString() : "N/A");
            }
        } else {
            log.error("Unsupported asset type: {} for identifier: {}", assetType, assetIdentifier);
            return null;
        }

        // Return AssetPriceInfo only if currentPrice is available, as it's essential.
        // Downstream services (like PortfolioCalculatorService) expect currentPrice.
        // previousPrice can be null and is handled downstream.
        if (currentPrice != null) {
            return new AssetPriceInfo(assetIdentifier, currentPrice, previousPrice);
        } else {
            log.warn("Current price not found for asset: {}, type: {}, date: {}. Cannot create valid AssetPriceInfo.",
                    assetIdentifier, assetType, date);
            return null; 
        }
    }

    /**
     * 获取多个资产在指定日期的价格信息，单个资产价格信息展现方式请见getAssetPriceInfo方法
     * 
     * @param assetIdentifiersWithTypes 资产标识符与资产类型的映射Map，key为资产标识符，value为资产类型
     * @param date 指定的日期
     * @return 包含资产价格信息的Map，key为资产标识符，value为AssetPriceInfo对象
     *         如果某个资产的价格信息未找到，则该资产不会包含在返回的Map中
     * @throws IllegalArgumentException 如果输入的assetIdentifiersWithTypes为空或date为空
     * @throws DataAccessException 如果访问数据源时发生错误
     * @see getAssetPriceInfo
     */
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
                log.warn("Price information not found for asset: {}, type: {} on date: {}", assetIdentifier, assetType, date);
            }
        }
        return results;
    }
} 
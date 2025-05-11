package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.AssetType;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockInfoDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockInfo;
import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundInfoDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundInfo;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetInfo;
import world.willfrog.alphafrogmicro.portfolioservice.service.AssetInfoService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AssetInfoServiceImpl implements AssetInfoService {

    @Autowired
    private StockInfoDao stockInfoDao;

    @Autowired
    private FundInfoDao fundInfoDao;

    /**
     * 根据资产标识符和资产类型获取资产的基本信息。
     * 
     * <p>该方法会根据传入的资产类型(AssetType)来决定查询哪种资产的信息：
     * <ul>
     *   <li>当资产类型为STOCK时，会通过stockInfoDao查询股票信息表，获取股票名称等信息</li>
     *   <li>当资产类型为FUND_ETF时，会通过fundInfoDao查询基金信息表，获取基金名称等信息</li>
     * </ul>
     * 
     * <p>查询逻辑说明：
     * <ol>
     *   <li>首先根据assetIdentifier(资产代码)查询对应的信息表</li>
     *   <li>如果查询结果不为空，则取第一个匹配的结果(假设查询结果已按相关性排序)</li>
     *   <li>将查询到的资产名称等信息封装到AssetInfo对象中返回</li>
     * </ol>
     * 
     * <p>异常处理：
     * <ul>
     *   <li>如果查询结果为空，会记录警告日志并返回null</li>
     *   <li>如果资产类型不被支持，会记录错误日志并返回null</li>
     * </ul>
     * 
     * @param assetIdentifier 资产唯一标识符(如股票代码、基金代码)
     * @param assetType 资产类型枚举值(如STOCK、FUND_ETF)
     * @return 包含资产信息的AssetInfo对象，如果未找到或类型不支持则返回null
     * @see AssetType
     * @see AssetInfo
     */
    @Override
    public AssetInfo getAssetInfo(String assetIdentifier, AssetType assetType) {
        log.info("Fetching info for asset: {}, type: {}", assetIdentifier, assetType);
        if (assetType == AssetType.STOCK) {
            List<StockInfo> stockInfos = stockInfoDao.getStockInfoByTsCode(assetIdentifier);
            if (stockInfos != null && !stockInfos.isEmpty()) {
                // 我们默认查询返回结果的第一个元素是匹配上的
                StockInfo stockInfo = stockInfos.get(0);
                if (stockInfo != null) {
                    // log.info("Found stock info for {}: {}", assetIdentifier, stockInfo.getName());
                    return new AssetInfo(assetIdentifier, assetType, stockInfo.getName());
                }
            }
            log.warn("Stock info not found for ts_code: {}", assetIdentifier);
            return null;
        } else if (assetType == AssetType.FUND_ETF) {
            List<FundInfo> fundInfos = fundInfoDao.getFundInfoByTsCode(assetIdentifier);
            if (fundInfos != null && !fundInfos.isEmpty()) {
                // 我们默认查询返回结果的第一个元素是匹配上的
                FundInfo fundInfo = fundInfos.get(0);
                if (fundInfo != null) {
                    // log.info("Found fund info for {}: {}", assetIdentifier, fundInfo.getName());
                    return new AssetInfo(assetIdentifier, assetType, fundInfo.getName());
                }
            }
            log.warn("Fund/ETF info not found for ts_code: {}", assetIdentifier);
            return null;
        }
        log.error("Unsupported asset type: {} for identifier: {}", assetType, assetIdentifier);
        return null; // 或者抛出异常
    }

    @Override
    public Map<String, AssetInfo> getMultipleAssetInfo(Map<String, AssetType> assetIdentifiersWithTypes) {
        Map<String, AssetInfo> results = new HashMap<>();
        for (Map.Entry<String, AssetType> entry : assetIdentifiersWithTypes.entrySet()) {
            String assetIdentifier = entry.getKey();
            AssetType assetType = entry.getValue();
            AssetInfo info = getAssetInfo(assetIdentifier, assetType);
            if (info != null) {
                results.put(assetIdentifier, info);
            } else {
                log.warn("Info not found for asset: {}, type: {}", assetIdentifier, assetType);
            }
        }
        return results;
    }
} 
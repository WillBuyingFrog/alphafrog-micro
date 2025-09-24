package world.willfrog.alphafrogmicro.portfolioservice.service;

import world.willfrog.alphafrogmicro.common.pojo.portfolio.AssetType;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetPriceInfo;

import java.time.LocalDate;
import java.util.Map;

public interface AssetPriceService {

    /**
     * 获取单个资产在指定日期的价格信息（当日价格和前一日价格）。
     *
     * @param assetIdentifier 资产代码
     * @param assetType 资产类型
     * @param date 指定日期
     * @return 资产价格信息，如果找不到则返回 null 或抛出异常
     */
    AssetPriceInfo getAssetPriceInfo(String assetIdentifier, AssetType assetType, LocalDate date);

    /**
     * 批量获取多个资产在指定日期的价格信息。
     *
     * @param assetIdentifiersWithTypes 资产代码和类型的列表/Map
     * @param date 指定日期
     * @return Key为assetIdentifier，Value为AssetPriceInfo的Map
     */
    // A more specific input than List<Map.Entry<String, AssetType>> might be better,
    // e.g., a dedicated simple class or record List<AssetKey> if many params per asset.
    // For now, using a Map for simplicity to pass identifier and type together.
    Map<String, AssetPriceInfo> getMultipleAssetPriceInfo(Map<String, AssetType> assetIdentifiersWithTypes, LocalDate date);

} 
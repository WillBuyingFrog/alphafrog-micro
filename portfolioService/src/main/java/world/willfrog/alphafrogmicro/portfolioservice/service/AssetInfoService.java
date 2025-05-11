package world.willfrog.alphafrogmicro.portfolioservice.service;

import world.willfrog.alphafrogmicro.common.pojo.portfolio.AssetType;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetInfo;

import java.util.List;
import java.util.Map;

public interface AssetInfoService {

    /**
     * 获取单个资产的基本信息 (例如名称)。
     *
     * @param assetIdentifier 资产代码
     * @param assetType 资产类型
     * @return 资产基本信息，如果找不到则返回 null 或抛出异常
     */
    AssetInfo getAssetInfo(String assetIdentifier, AssetType assetType);

    /**
     * 批量获取多个资产的基本信息。
     *
     * @param assetIdentifiersWithTypes 资产代码和类型的列表/Map
     * @return Key为assetIdentifier，Value为AssetInfo的Map
     */
    Map<String, AssetInfo> getMultipleAssetInfo(Map<String, AssetType> assetIdentifiersWithTypes);

} 
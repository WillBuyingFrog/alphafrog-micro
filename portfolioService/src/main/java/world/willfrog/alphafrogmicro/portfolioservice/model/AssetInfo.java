package world.willfrog.alphafrogmicro.portfolioservice.model;

import lombok.Getter;
import lombok.AllArgsConstructor;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.AssetType;

@Getter
@AllArgsConstructor
public class AssetInfo {
    private String assetIdentifier;
    private AssetType assetType;
    private String assetName;

} 
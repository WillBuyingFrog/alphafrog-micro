package world.willfrog.alphafrogmicro.portfolioservice.model;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.AllArgsConstructor;

@Getter
@AllArgsConstructor
public class AssetPriceInfo {
    private String assetIdentifier;
    private BigDecimal currentPrice;    // 当日价格 (收盘价/净值)
    private BigDecimal previousPrice;   // 昨日价格 (收盘价/净值)


} 
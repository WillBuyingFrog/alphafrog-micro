package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockInfoDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundInfo;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockInfo;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.AssetType;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetInfo;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AssetInfoServiceImplTest {

    @Mock
    private StockInfoDao stockInfoDao;
    @Mock
    private FundInfoDao fundInfoDao;

    @InjectMocks
    private AssetInfoServiceImpl assetInfoService;

    private StockInfo stock1;
    private FundInfo fund1;

    @BeforeEach
    void setUp() {
        stock1 = new StockInfo();
        stock1.setTsCode("AAPL");
        stock1.setName("Apple Inc.");

        fund1 = new FundInfo();
        fund1.setTsCode("FUND01");
        fund1.setName("Test Fund 1");
    }

    @Test
    void getAssetInfo_forStock_whenExists_shouldReturnAssetInfo() {
        // Arrange
        when(stockInfoDao.getStockInfoByTsCode("AAPL")).thenReturn(Collections.singletonList(stock1));

        // Act
        AssetInfo info = assetInfoService.getAssetInfo("AAPL", AssetType.STOCK);

        // Assert
        assertThat(info).isNotNull();
        assertThat(info.getAssetIdentifier()).isEqualTo("AAPL");
        assertThat(info.getAssetName()).isEqualTo("Apple Inc.");
        assertThat(info.getAssetType()).isEqualTo(AssetType.STOCK);
    }

    @Test
    void getAssetInfo_forStock_whenNotExists_shouldReturnNull() {
        // Arrange
        when(stockInfoDao.getStockInfoByTsCode("UNKNOWN")).thenReturn(Collections.emptyList());

        // Act
        AssetInfo info = assetInfoService.getAssetInfo("UNKNOWN", AssetType.STOCK);

        // Assert
        assertThat(info).isNull();
    }
    
    @Test
    void getAssetInfo_forStock_whenDaoReturnsNullList_shouldReturnNull() {
        // Arrange
        when(stockInfoDao.getStockInfoByTsCode("AAPL")).thenReturn(null);

        // Act
        AssetInfo info = assetInfoService.getAssetInfo("AAPL", AssetType.STOCK);

        // Assert
        assertThat(info).isNull();
    }

    @Test
    void getAssetInfo_forFund_whenExists_shouldReturnAssetInfo() {
        // Arrange
        when(fundInfoDao.getFundInfoByTsCode("FUND01")).thenReturn(Collections.singletonList(fund1));

        // Act
        AssetInfo info = assetInfoService.getAssetInfo("FUND01", AssetType.FUND_ETF);

        // Assert
        assertThat(info).isNotNull();
        assertThat(info.getAssetIdentifier()).isEqualTo("FUND01");
        assertThat(info.getAssetName()).isEqualTo("Test Fund 1");
        assertThat(info.getAssetType()).isEqualTo(AssetType.FUND_ETF);
    }

    @Test
    void getAssetInfo_forFund_whenNotExists_shouldReturnNull() {
        // Arrange
        when(fundInfoDao.getFundInfoByTsCode("UNKNOWN_FUND")).thenReturn(Collections.emptyList());

        // Act
        AssetInfo info = assetInfoService.getAssetInfo("UNKNOWN_FUND", AssetType.FUND_ETF);

        // Assert
        assertThat(info).isNull();
    }

    @Test
    void getAssetInfo_forUnsupportedType_shouldReturnNull() {
        // Act
        AssetInfo info = assetInfoService.getAssetInfo("ANY_ID", null); // Or some other type not STOCK or FUND_ETF
        // Assert
        assertThat(info).isNull();
    }

    @Test
    void getMultipleAssetInfo_shouldReturnMapWithAvailableInfo() {
        // Arrange
        when(stockInfoDao.getStockInfoByTsCode("AAPL")).thenReturn(Collections.singletonList(stock1));
        when(fundInfoDao.getFundInfoByTsCode("FUND01")).thenReturn(Collections.singletonList(fund1));
        when(stockInfoDao.getStockInfoByTsCode("GOOG")).thenReturn(Collections.emptyList()); // GOOG info not found

        Map<String, AssetType> assets = new HashMap<>();
        assets.put("AAPL", AssetType.STOCK);
        assets.put("FUND01", AssetType.FUND_ETF);
        assets.put("GOOG", AssetType.STOCK);

        // Act
        Map<String, AssetInfo> infos = assetInfoService.getMultipleAssetInfo(assets);

        // Assert
        assertThat(infos).isNotNull();
        assertThat(infos).hasSize(2); // AAPL and FUND01
        assertThat(infos.get("AAPL").getAssetName()).isEqualTo("Apple Inc.");
        assertThat(infos.get("FUND01").getAssetName()).isEqualTo("Test Fund 1");
        assertThat(infos.containsKey("GOOG")).isFalse();
    }
} 
package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockInfoDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundInfo;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockInfo;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.AssetType;
import world.willfrog.alphafrogmicro.portfolioservice.model.AssetInfo;
import world.willfrog.alphafrogmicro.portfolioservice.service.AssetInfoService;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("it")
@Transactional // Rollback database changes after each test
class AssetInfoServiceImplIT {

    @Autowired
    private AssetInfoService assetInfoService;

    @Autowired
    private StockInfoDao stockInfoDao; // For inserting test data

    @Autowired
    private FundInfoDao fundInfoDao;   // For inserting test data

    @BeforeEach
    void setUpTestData() {
        StockInfo aapl = new StockInfo();
        aapl.setTsCode("IT_AAPL_INFO"); 
        aapl.setName("Apple Inc. (Info IT)");
        aapl.setSymbol("AAPL_INFO");
        stockInfoDao.insertStockInfo(aapl); 

        FundInfo fund01 = new FundInfo();
        fund01.setTsCode("IT_FUND01_INFO"); 
        fund01.setName("Test Fund 1 (Info IT)");
        fundInfoDao.insertFundInfo(fund01); 
    }

    @Test
    void getAssetInfo_forStock_whenExists_shouldReturnCorrectInfo() {
        AssetInfo info = assetInfoService.getAssetInfo("IT_AAPL_INFO", AssetType.STOCK);

        assertThat(info).isNotNull();
        assertThat(info.getAssetIdentifier()).isEqualTo("IT_AAPL_INFO");
        assertThat(info.getAssetName()).isEqualTo("Apple Inc. (Info IT)");
        assertThat(info.getAssetType()).isEqualTo(AssetType.STOCK);
    }

    @Test
    void getAssetInfo_forFund_whenExists_shouldReturnCorrectInfo() {
        AssetInfo info = assetInfoService.getAssetInfo("IT_FUND01_INFO", AssetType.FUND_ETF);

        assertThat(info).isNotNull();
        assertThat(info.getAssetIdentifier()).isEqualTo("IT_FUND01_INFO");
        assertThat(info.getAssetName()).isEqualTo("Test Fund 1 (Info IT)");
        assertThat(info.getAssetType()).isEqualTo(AssetType.FUND_ETF);
    }

    @Test
    void getAssetInfo_forStock_whenNotExists_shouldReturnNull() {
        AssetInfo info = assetInfoService.getAssetInfo("NON_EXISTENT_STOCK_INFO", AssetType.STOCK);
        assertThat(info).isNull();
    }

    @Test
    void getAssetInfo_forFund_whenNotExists_shouldReturnNull() {
        AssetInfo info = assetInfoService.getAssetInfo("NON_EXISTENT_FUND_INFO", AssetType.FUND_ETF);
        assertThat(info).isNull();
    }
    
    @Test
    void getAssetInfo_forUnsupportedType_shouldReturnNull() {
        AssetInfo info = assetInfoService.getAssetInfo("IT_AAPL_INFO", null); 
        assertThat(info).isNull();
    }

    @Test
    void getMultipleAssetInfo_shouldFetchAllAvailableInfo() {
        Map<String, AssetType> assetsToFetch = new HashMap<>();
        assetsToFetch.put("IT_AAPL_INFO", AssetType.STOCK);
        assetsToFetch.put("IT_FUND01_INFO", AssetType.FUND_ETF);
        assetsToFetch.put("NON_EXISTENT_ID_INFO", AssetType.STOCK);

        Map<String, AssetInfo> infos = assetInfoService.getMultipleAssetInfo(assetsToFetch);

        assertThat(infos).isNotNull();
        assertThat(infos).hasSize(2); 
        assertThat(infos.containsKey("IT_AAPL_INFO")).isTrue();
        assertThat(infos.get("IT_AAPL_INFO").getAssetName()).isEqualTo("Apple Inc. (Info IT)");
        assertThat(infos.containsKey("IT_FUND01_INFO")).isTrue();
        assertThat(infos.get("IT_FUND01_INFO").getAssetName()).isEqualTo("Test Fund 1 (Info IT)");
        assertThat(infos.containsKey("NON_EXISTENT_ID_INFO")).isFalse();
    }
} 
package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional; // Important for rolling back changes

import world.willfrog.alphafrogmicro.common.dao.portfolio.PortfolioDao;
import world.willfrog.alphafrogmicro.common.dao.portfolio.PortfolioHoldingDao;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.AssetType;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.Portfolio;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.PortfolioHolding;
import world.willfrog.alphafrogmicro.portfolioservice.service.PortfolioService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("it")
@Transactional // Rollback database changes after each test
class PortfolioServiceImplIT {

    @Autowired
    private PortfolioService portfolioService;

    @Autowired
    private PortfolioDao portfolioDao; // For direct data manipulation

    @Autowired
    private PortfolioHoldingDao portfolioHoldingDao; // For direct data manipulation

    private Portfolio savedPortfolio1;
    private Portfolio savedPortfolio2;
    private PortfolioHolding savedHolding1;
    private PortfolioHolding savedHolding2;

    @BeforeEach
    void setUpDatabase() {
        Portfolio p1 = new Portfolio();
        p1.setUserId("userIT1");
        p1.setName("IT Portfolio 1");
        portfolioDao.insert(p1); 
        savedPortfolio1 = p1;

        Portfolio p2 = new Portfolio();
        p2.setUserId("userIT2");
        p2.setName("IT Portfolio 2");
        portfolioDao.insert(p2);
        savedPortfolio2 = p2;
        
        PortfolioHolding h1 = new PortfolioHolding();
        h1.setPortfolio(savedPortfolio1); 
        h1.setAssetIdentifier("AAPL");
        h1.setAssetType(AssetType.STOCK);
        h1.setQuantity(new BigDecimal("100"));
        h1.setAverageCostPrice(new BigDecimal("150.00"));
        portfolioHoldingDao.insert(h1);
        savedHolding1 = h1;

        PortfolioHolding h2 = new PortfolioHolding();
        h2.setPortfolio(savedPortfolio1);
        h2.setAssetIdentifier("MSFT");
        h2.setAssetType(AssetType.STOCK);
        h2.setQuantity(new BigDecimal("50"));
        h2.setAverageCostPrice(new BigDecimal("280.00"));
        portfolioHoldingDao.insert(h2);
        savedHolding2 = h2;
    }
    

    @Test
    void createPortfolio_shouldPersistAndReturnPortfolio() {
        Portfolio newPortfolio = new Portfolio();
        newPortfolio.setUserId("userIT_new");
        newPortfolio.setName("New IT Portfolio");

        Portfolio created = portfolioService.createPortfolio(newPortfolio);

        assertThat(created).isNotNull();
        assertThat(created.getId()).isNotNull(); 
        assertThat(created.getName()).isEqualTo("New IT Portfolio");

        Optional<Portfolio> foundInDb = portfolioService.getPortfolioById(created.getId());
        assertThat(foundInDb).isPresent();
        assertThat(foundInDb.get().getName()).isEqualTo("New IT Portfolio");
    }

    @Test
    void getPortfolioById_whenExists_shouldReturnPortfolio() {
        Optional<Portfolio> found = portfolioService.getPortfolioById(savedPortfolio1.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("IT Portfolio 1");
    }

    @Test
    void getPortfolioById_whenNotExists_shouldReturnEmpty() {
        Optional<Portfolio> found = portfolioService.getPortfolioById(99999L); 
        assertThat(found).isNotPresent();
    }

    @Test
    void getPortfolioWithHoldingsById_whenExists_shouldReturnPortfolioAndItsHoldings() {
        Optional<Portfolio> found = portfolioService.getPortfolioWithHoldingsById(savedPortfolio1.getId());
        
        assertThat(found).isPresent();
        Portfolio p = found.get();
        assertThat(p.getName()).isEqualTo("IT Portfolio 1");
        assertThat(p.getHoldings()).isNotNull().hasSize(2);
        assertThat(p.getHoldings()).extracting(PortfolioHolding::getAssetIdentifier).containsExactlyInAnyOrder("AAPL", "MSFT");
    }
    
    @Test
    void getPortfolioWithHoldingsById_whenPortfolioHasNoHoldings_shouldReturnPortfolioWithEmptyHoldings() {
        Optional<Portfolio> found = portfolioService.getPortfolioWithHoldingsById(savedPortfolio2.getId()); 
        
        assertThat(found).isPresent();
        Portfolio p = found.get();
        assertThat(p.getName()).isEqualTo("IT Portfolio 2");
        assertThat(p.getHoldings()).isNotNull().isEmpty();
    }

    @Test
    void getPortfoliosByUserId_shouldReturnMatchingPortfolios() {
        List<Portfolio> portfolios = portfolioService.getPortfoliosByUserId("userIT1");
        assertThat(portfolios).isNotNull().hasSize(1);
        assertThat(portfolios.get(0).getName()).isEqualTo("IT Portfolio 1");

        List<Portfolio> emptyList = portfolioService.getPortfoliosByUserId("non_existent_user");
        assertThat(emptyList).isNotNull().isEmpty();
    }

    @Test
    void updatePortfolio_shouldModifyExistingPortfolio() {
        Portfolio toUpdate = portfolioService.getPortfolioById(savedPortfolio1.getId()).orElseThrow();
        toUpdate.setName("Updated IT Portfolio 1 Name");

        Portfolio updated = portfolioService.updatePortfolio(toUpdate);
        assertThat(updated).isNotNull();
        assertThat(updated.getName()).isEqualTo("Updated IT Portfolio 1 Name");

        Optional<Portfolio> foundInDb = portfolioService.getPortfolioById(savedPortfolio1.getId());
        assertThat(foundInDb).isPresent();
        assertThat(foundInDb.get().getName()).isEqualTo("Updated IT Portfolio 1 Name");
    }

    @Test
    void deletePortfolio_shouldRemovePortfolioAndAssociatedHoldingsIfCascadedOrHandled() {
        Long portfolioIdToDelete = savedPortfolio1.getId();
        
        portfolioService.deletePortfolio(portfolioIdToDelete);

        Optional<Portfolio> found = portfolioService.getPortfolioById(portfolioIdToDelete);
        assertThat(found).isNotPresent();
        
         List<PortfolioHolding> holdingsAfterDelete = portfolioHoldingDao.findByPortfolioId(portfolioIdToDelete);
         assertThat(holdingsAfterDelete).isEmpty();
    }

    @Test
    void addHoldingToPortfolio_shouldPersistNewHolding() {
        PortfolioHolding newHolding = new PortfolioHolding();
        newHolding.setAssetIdentifier("GOOG");
        newHolding.setAssetType(AssetType.STOCK);
        newHolding.setQuantity(new BigDecimal("25"));
        newHolding.setAverageCostPrice(new BigDecimal("1200.00"));

        PortfolioHolding added = portfolioService.addHoldingToPortfolio(savedPortfolio2.getId(), newHolding);

        assertThat(added).isNotNull();
        assertThat(added.getId()).isNotNull();
        assertThat(added.getAssetIdentifier()).isEqualTo("GOOG");
        assertThat(added.getPortfolio().getId()).isEqualTo(savedPortfolio2.getId());

        Optional<PortfolioHolding> foundInDb = portfolioService.getHoldingById(added.getId());
        assertThat(foundInDb).isPresent();
        assertThat(foundInDb.get().getAssetIdentifier()).isEqualTo("GOOG");
    }

    @Test
    void updateHoldingInPortfolio_shouldModifyExistingHolding() {
        PortfolioHolding toUpdate = portfolioService.getHoldingById(savedHolding1.getId()).orElseThrow();
        toUpdate.setQuantity(new BigDecimal("120"));
        toUpdate.setAverageCostPrice(new BigDecimal("155.00"));

        PortfolioHolding updated = portfolioService.updateHoldingInPortfolio(savedPortfolio1.getId(), toUpdate);

        assertThat(updated).isNotNull();
        assertThat(updated.getQuantity()).isEqualByComparingTo(new BigDecimal("120"));
        assertThat(updated.getAverageCostPrice()).isEqualByComparingTo(new BigDecimal("155.00"));

        Optional<PortfolioHolding> foundInDb = portfolioService.getHoldingById(savedHolding1.getId());
        assertThat(foundInDb).isPresent();
        assertThat(foundInDb.get().getQuantity()).isEqualByComparingTo(new BigDecimal("120"));
    }

    @Test
    void removeHoldingFromPortfolio_shouldDeleteHolding() {
        Long holdingIdToRemove = savedHolding1.getId();
        Long portfolioId = savedHolding1.getPortfolio().getId();

        portfolioService.removeHoldingFromPortfolio(portfolioId, holdingIdToRemove);

        Optional<PortfolioHolding> found = portfolioService.getHoldingById(holdingIdToRemove);
        assertThat(found).isNotPresent();
    }

    @Test
    void getHoldingsByPortfolioId_shouldReturnAllHoldingsForPortfolio() {
        List<PortfolioHolding> holdings = portfolioService.getHoldingsByPortfolioId(savedPortfolio1.getId());
        assertThat(holdings).isNotNull().hasSize(2);
        assertThat(holdings).extracting(PortfolioHolding::getAssetIdentifier).containsExactlyInAnyOrder("AAPL", "MSFT");
    }

    @Test
    void getHoldingById_whenExists_shouldReturnHolding() {
        Optional<PortfolioHolding> found = portfolioService.getHoldingById(savedHolding1.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getAssetIdentifier()).isEqualTo("AAPL");
    }
    
    @Test
    void getHoldingById_whenNotExists_shouldReturnEmpty() {
        Optional<PortfolioHolding> found = portfolioService.getHoldingById(8888L); 
        assertThat(found).isNotPresent();
    }
} 
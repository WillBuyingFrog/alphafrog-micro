package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
// Import other necessary classes, including the service itself and its dependencies

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

import world.willfrog.alphafrogmicro.common.dao.portfolio.PortfolioDao;
import world.willfrog.alphafrogmicro.common.dao.portfolio.PortfolioHoldingDao;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.Portfolio;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.PortfolioHolding;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceImplTest {

    @Mock
    private PortfolioDao portfolioDao;

    @Mock
    private PortfolioHoldingDao portfolioHoldingDao;

    @InjectMocks
    private PortfolioServiceImpl portfolioService;

    private Portfolio portfolio1;
    private PortfolioHolding holding1;

    @BeforeEach
    void setUp() {
        portfolio1 = new Portfolio();
        portfolio1.setId(1L);
        portfolio1.setUserId("user123");
        portfolio1.setName("My Test Portfolio");

        holding1 = new PortfolioHolding();
        holding1.setId(101L);
        holding1.setPortfolio(portfolio1);
        holding1.setAssetIdentifier("AAPL");
        holding1.setQuantity(new BigDecimal("100"));
    }

    @Test
    void createPortfolio_shouldReturnCreatedPortfolio() {
        // Arrange
        Portfolio newPortfolio = new Portfolio();
        newPortfolio.setUserId("user123");
        newPortfolio.setName("New Portfolio");
        
        // Mock DAO to simulate ID generation or successful insert
        doAnswer(invocation -> {
            Portfolio p = invocation.getArgument(0);
            p.setId(2L); // Simulate ID generation
            return null; // void method
        }).when(portfolioDao).insert(any(Portfolio.class));

        // Act
        Portfolio created = portfolioService.createPortfolio(newPortfolio);

        // Assert
        assertThat(created).isNotNull();
        assertThat(created.getId()).isEqualTo(2L);
        assertThat(created.getName()).isEqualTo("New Portfolio");
        verify(portfolioDao).insert(newPortfolio);
    }

    @Test
    void getPortfolioById_whenExists_shouldReturnPortfolio() {
        // Arrange
        when(portfolioDao.findById(1L)).thenReturn(portfolio1);

        // Act
        Optional<Portfolio> found = portfolioService.getPortfolioById(1L);

        // Assert
        assertThat(found).isPresent();
        assertThat(found.get()).isEqualTo(portfolio1);
    }

    @Test
    void getPortfolioById_whenNotExists_shouldReturnEmpty() {
        // Arrange
        when(portfolioDao.findById(99L)).thenReturn(null);

        // Act
        Optional<Portfolio> found = portfolioService.getPortfolioById(99L);

        // Assert
        assertThat(found).isNotPresent();
    }

    @Test
    void getPortfolioWithHoldingsById_whenExists_shouldReturnPortfolioWithHoldings() {
        // Arrange
        when(portfolioDao.findById(1L)).thenReturn(portfolio1);
        when(portfolioHoldingDao.findByPortfolioId(1L)).thenReturn(Collections.singletonList(holding1));

        // Act
        Optional<Portfolio> found = portfolioService.getPortfolioWithHoldingsById(1L);

        // Assert
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("My Test Portfolio");
        assertThat(found.get().getHoldings()).isNotNull();
        assertThat(found.get().getHoldings()).hasSize(1);
        assertThat(found.get().getHoldings().get(0).getAssetIdentifier()).isEqualTo("AAPL");
    }
    
    @Test
    void getPortfolioWithHoldingsById_whenPortfolioNotExists_shouldReturnEmpty() {
        // Arrange
        when(portfolioDao.findById(1L)).thenReturn(null);

        // Act
        Optional<Portfolio> found = portfolioService.getPortfolioWithHoldingsById(1L);

        // Assert
        assertThat(found).isNotPresent();
        verify(portfolioHoldingDao, never()).findByPortfolioId(anyLong());
    }


    @Test
    void getPortfoliosByUserId_shouldReturnListOfPortfolios() {
        // Arrange
        when(portfolioDao.findByUserId("user123")).thenReturn(Collections.singletonList(portfolio1));

        // Act
        List<Portfolio> portfolios = portfolioService.getPortfoliosByUserId("user123");

        // Assert
        assertThat(portfolios).isNotNull();
        assertThat(portfolios).hasSize(1);
        assertThat(portfolios.get(0).getName()).isEqualTo("My Test Portfolio");
    }

    @Test
    void updatePortfolio_whenIdExists_shouldReturnUpdatedPortfolio() {
        // Arrange
        portfolio1.setName("Updated Portfolio Name");
        // No need to mock portfolioDao.update for this unit test as it's void and we check the returned object

        // Act
        Portfolio updated = portfolioService.updatePortfolio(portfolio1);

        // Assert
        assertThat(updated).isNotNull();
        assertThat(updated.getName()).isEqualTo("Updated Portfolio Name");
        verify(portfolioDao).update(portfolio1);
    }
    
    @Test
    void updatePortfolio_whenIdIsNull_shouldReturnNullAndLogError() {
        // Arrange
        Portfolio portfolioWithoutId = new Portfolio();
        portfolioWithoutId.setName("Portfolio Without ID");

        // Act
        Portfolio updated = portfolioService.updatePortfolio(portfolioWithoutId);

        // Assert
        assertThat(updated).isNull();
        verify(portfolioDao, never()).update(any(Portfolio.class));
        // Add log verification if SLF4J test support is configured
    }

    @Test
    void deletePortfolio_shouldCallDaoDelete() {
        // Arrange
        Long portfolioIdToDelete = 1L;

        // Act
        portfolioService.deletePortfolio(portfolioIdToDelete);

        // Assert
        verify(portfolioDao).deleteById(portfolioIdToDelete);
    }

    @Test
    void addHoldingToPortfolio_whenPortfolioExists_shouldAddAndReturnHolding() {
        // Arrange
        PortfolioHolding newHolding = new PortfolioHolding();
        newHolding.setAssetIdentifier("MSFT");
        newHolding.setQuantity(new BigDecimal("50"));

        when(portfolioDao.findById(1L)).thenReturn(portfolio1);
        doAnswer(invocation -> {
            PortfolioHolding h = invocation.getArgument(0);
            h.setId(102L); // Simulate ID generation
            return null;
        }).when(portfolioHoldingDao).insert(any(PortfolioHolding.class));

        // Act
        PortfolioHolding addedHolding = portfolioService.addHoldingToPortfolio(1L, newHolding);

        // Assert
        assertThat(addedHolding).isNotNull();
        assertThat(addedHolding.getId()).isEqualTo(102L);
        assertThat(addedHolding.getAssetIdentifier()).isEqualTo("MSFT");
        assertThat(addedHolding.getPortfolio()).isEqualTo(portfolio1);
        verify(portfolioHoldingDao).insert(newHolding);
    }
    
    @Test
    void addHoldingToPortfolio_whenPortfolioNotExists_shouldReturnNull() {
        // Arrange
        PortfolioHolding newHolding = new PortfolioHolding();
        newHolding.setAssetIdentifier("MSFT");
        when(portfolioDao.findById(99L)).thenReturn(null);

        // Act
        PortfolioHolding addedHolding = portfolioService.addHoldingToPortfolio(99L, newHolding);

        // Assert
        assertThat(addedHolding).isNull();
        verify(portfolioHoldingDao, never()).insert(any(PortfolioHolding.class));
    }

    @Test
    void updateHoldingInPortfolio_whenIdExists_shouldReturnUpdatedHolding() {
        // Arrange
        holding1.setQuantity(new BigDecimal("150"));
        // No need to mock portfolioHoldingDao.update, similar to portfolioDao.update

        // Act
        PortfolioHolding updatedHolding = portfolioService.updateHoldingInPortfolio(1L, holding1);

        // Assert
        assertThat(updatedHolding).isNotNull();
        assertThat(updatedHolding.getQuantity()).isEqualByComparingTo(new BigDecimal("150"));
        verify(portfolioHoldingDao).update(holding1);
    }
    
    @Test
    void updateHoldingInPortfolio_whenIdIsNull_shouldReturnNull() {
        // Arrange
        PortfolioHolding holdingWithoutId = new PortfolioHolding();

        // Act
        PortfolioHolding updatedHolding = portfolioService.updateHoldingInPortfolio(1L, holdingWithoutId);

        // Assert
        assertThat(updatedHolding).isNull();
        verify(portfolioHoldingDao, never()).update(any(PortfolioHolding.class));
    }

    @Test
    void removeHoldingFromPortfolio_shouldCallDaoDelete() {
        // Arrange
        Long portfolioId = 1L;
        Long holdingIdToDelete = 101L;

        // Act
        portfolioService.removeHoldingFromPortfolio(portfolioId, holdingIdToDelete);

        // Assert
        verify(portfolioHoldingDao).deleteById(holdingIdToDelete);
    }

    @Test
    void getHoldingsByPortfolioId_shouldReturnListOfHoldings() {
        // Arrange
        when(portfolioHoldingDao.findByPortfolioId(1L)).thenReturn(Collections.singletonList(holding1));

        // Act
        List<PortfolioHolding> holdings = portfolioService.getHoldingsByPortfolioId(1L);

        // Assert
        assertThat(holdings).isNotNull();
        assertThat(holdings).hasSize(1);
        assertThat(holdings.get(0).getAssetIdentifier()).isEqualTo("AAPL");
    }

    @Test
    void getHoldingById_whenExists_shouldReturnHolding() {
        // Arrange
        when(portfolioHoldingDao.findById(101L)).thenReturn(holding1);

        // Act
        Optional<PortfolioHolding> found = portfolioService.getHoldingById(101L);

        // Assert
        assertThat(found).isPresent();
        assertThat(found.get()).isEqualTo(holding1);
    }

    @Test
    void getHoldingById_whenNotExists_shouldReturnEmpty() {
        // Arrange
        when(portfolioHoldingDao.findById(999L)).thenReturn(null);

        // Act
        Optional<PortfolioHolding> found = portfolioService.getHoldingById(999L);

        // Assert
        assertThat(found).isNotPresent();
    }
} 
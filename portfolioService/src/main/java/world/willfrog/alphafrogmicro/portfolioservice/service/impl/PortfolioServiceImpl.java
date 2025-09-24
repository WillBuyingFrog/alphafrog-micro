package world.willfrog.alphafrogmicro.portfolioservice.service.impl;


import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import world.willfrog.alphafrogmicro.common.dao.portfolio.PortfolioHoldingDao; // Placeholder - replace with your actual DAO
import world.willfrog.alphafrogmicro.common.dao.portfolio.PortfolioDao; // Placeholder - replace with your actual DAO
import world.willfrog.alphafrogmicro.common.pojo.portfolio.Portfolio;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.PortfolioHolding;
import world.willfrog.alphafrogmicro.portfolioservice.service.PortfolioService;

import java.util.List;
import java.util.Optional;


@Service
@Slf4j
public class PortfolioServiceImpl implements PortfolioService {

    private final PortfolioDao portfolioDao; // Placeholder
    private final PortfolioHoldingDao portfolioHoldingDao; // Placeholder


    public PortfolioServiceImpl(PortfolioDao portfolioDao, 
                              PortfolioHoldingDao portfolioHoldingDao) {
        this.portfolioDao = portfolioDao;
        this.portfolioHoldingDao = portfolioHoldingDao;
    }

    @Override
    @Transactional
    public Portfolio createPortfolio(Portfolio portfolio) {
        // Assuming portfolio object passed here might not have an ID yet.
        // The DAO's insert method should populate the ID if auto-generated.
        portfolioDao.insert(portfolio);
        log.info("Created portfolio with ID: {}", portfolio.getId());
        return portfolio;
    }

    @Override
    public Optional<Portfolio> getPortfolioById(Long portfolioId) {
        Portfolio portfolio = portfolioDao.findById(portfolioId);
        return Optional.ofNullable(portfolio);
    }

    @Override
    public Optional<Portfolio> getPortfolioWithHoldingsById(Long portfolioId) {
        Portfolio portfolio = portfolioDao.findById(portfolioId);
        if (portfolio != null) {
            List<PortfolioHolding> holdings = portfolioHoldingDao.findByPortfolioId(portfolioId);
            portfolio.setHoldings(holdings); 
            return Optional.of(portfolio);
        }
        return Optional.empty();
    }

    @Override
    public List<Portfolio> getPortfoliosByUserId(String userId) {
        return portfolioDao.findByUserId(userId);
    }

    @Override
    @Transactional
    public Portfolio updatePortfolio(Portfolio portfolio) {
        // Ensure portfolio has an ID for update
        if (portfolio.getId() == null) {
            log.error("Portfolio ID is null, cannot update.");
            // Or throw IllegalArgumentException
            return null; 
        }
        portfolioDao.update(portfolio);
        return portfolio;
    }

    @Override
    @Transactional
    public void deletePortfolio(Long portfolioId) {
        // Consider business logic: what happens to holdings? 
        // Current MyBatis DAOs would require separate calls to delete holdings first if there's a FK constraint
        // or use cascade delete at DB level.
        // For simplicity, assuming holdings are deleted separately or cascaded.
        // If not, delete holdings first:
        // portfolioHoldingRepository.deleteByPortfolioId(portfolioId);
        portfolioDao.deleteById(portfolioId);
        log.info("Deleted portfolio with ID: {}", portfolioId);
    }

    @Override
    @Transactional
    public PortfolioHolding addHoldingToPortfolio(Long portfolioId, PortfolioHolding holding) {
        Optional<Portfolio> portfolioOpt = getPortfolioById(portfolioId);
        if (portfolioOpt.isPresent()) {
            holding.setPortfolio(portfolioOpt.get()); // Set the portfolio reference
            // Ensure the holding.portfolio.id is correctly used by MyBatis insert if needed
            portfolioHoldingDao.insert(holding);
            log.info("Added holding with ID: {} to portfolio ID: {}", holding.getId(), portfolioId);
            return holding;
        } else {
            log.error("Portfolio with ID: {} not found. Cannot add holding.", portfolioId);
            // Or throw exception
            return null;
        }
    }

    @Override
    @Transactional
    public PortfolioHolding updateHoldingInPortfolio(Long portfolioId, PortfolioHolding holding) {
        // Ensure portfolioId matches the one in holding if it's set, or that holding belongs to this portfolio.
        // For simplicity, assuming holding object is self-contained for update by its ID.
        if (holding.getId() == null) {
            log.error("Holding ID is null, cannot update.");
            return null;
        }
        // Optional: Check if holding actually belongs to portfolioId before updating
        portfolioHoldingDao.update(holding);
        return holding;
    }

    @Override
    @Transactional
    public void removeHoldingFromPortfolio(Long portfolioId, Long holdingId) {
        // Optional: Check if holdingId actually belongs to portfolioId before deleting
        portfolioHoldingDao.deleteById(holdingId);
        log.info("Removed holding ID: {} from portfolio ID: {}", holdingId, portfolioId);
    }

    @Override
    public List<PortfolioHolding> getHoldingsByPortfolioId(Long portfolioId) {
        return portfolioHoldingDao.findByPortfolioId(portfolioId);
    }

    @Override
    public Optional<PortfolioHolding> getHoldingById(Long holdingId) {
        PortfolioHolding holding = portfolioHoldingDao.findById(holdingId);
        return Optional.ofNullable(holding);
    }
} 
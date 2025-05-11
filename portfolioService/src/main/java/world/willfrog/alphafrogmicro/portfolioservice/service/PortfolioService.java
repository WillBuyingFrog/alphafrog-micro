package world.willfrog.alphafrogmicro.portfolioservice.service;

import world.willfrog.alphafrogmicro.common.pojo.portfolio.Portfolio;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.PortfolioHolding;

import java.util.List;
import java.util.Optional;

public interface PortfolioService {

    Portfolio createPortfolio(Portfolio portfolio);

    Optional<Portfolio> getPortfolioById(Long portfolioId);

    // Optional: Method to get portfolio with all its holdings eagerly fetched
    Optional<Portfolio> getPortfolioWithHoldingsById(Long portfolioId);

    List<Portfolio> getPortfoliosByUserId(String userId);

    Portfolio updatePortfolio(Portfolio portfolio);

    void deletePortfolio(Long portfolioId);

    PortfolioHolding addHoldingToPortfolio(Long portfolioId, PortfolioHolding holding);

    PortfolioHolding updateHoldingInPortfolio(Long portfolioId, PortfolioHolding holding);

    void removeHoldingFromPortfolio(Long portfolioId, Long holdingId);

    List<PortfolioHolding> getHoldingsByPortfolioId(Long portfolioId);

    Optional<PortfolioHolding> getHoldingById(Long holdingId);

} 
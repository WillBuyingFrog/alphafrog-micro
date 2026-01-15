package world.willfrog.alphafrogmicro.portfolioservice.service;

import world.willfrog.alphafrogmicro.portfolioservice.dto.PageResult;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PortfolioCreateRequest;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PortfolioResponse;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PortfolioUpdateRequest;

public interface PortfolioService {

    PortfolioResponse create(String userId, PortfolioCreateRequest request);

    PageResult<PortfolioResponse> list(String userId, String status, String keyword, int page, int size);

    PortfolioResponse getById(Long id, String userId);

    PortfolioResponse update(Long id, String userId, PortfolioUpdateRequest request);

    void archive(Long id, String userId);
}

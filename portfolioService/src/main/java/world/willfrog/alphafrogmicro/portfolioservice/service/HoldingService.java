package world.willfrog.alphafrogmicro.portfolioservice.service;

import world.willfrog.alphafrogmicro.portfolioservice.dto.HoldingResponse;
import world.willfrog.alphafrogmicro.portfolioservice.dto.HoldingUpsertRequest;

import java.util.List;

public interface HoldingService {

    List<HoldingResponse> upsertHoldings(Long portfolioId, String userId, HoldingUpsertRequest request);

    List<HoldingResponse> listHoldings(Long portfolioId, String userId);
}

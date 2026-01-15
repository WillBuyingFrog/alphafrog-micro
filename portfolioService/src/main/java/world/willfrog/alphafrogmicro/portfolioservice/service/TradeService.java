package world.willfrog.alphafrogmicro.portfolioservice.service;

import world.willfrog.alphafrogmicro.portfolioservice.dto.PageResult;
import world.willfrog.alphafrogmicro.portfolioservice.dto.TradeCreateRequest;
import world.willfrog.alphafrogmicro.portfolioservice.dto.TradeResponse;

import java.time.OffsetDateTime;

public interface TradeService {

    void createTrades(Long portfolioId, String userId, TradeCreateRequest request);

    PageResult<TradeResponse> listTrades(Long portfolioId,
                                         String userId,
                                         OffsetDateTime from,
                                         OffsetDateTime to,
                                         String eventType,
                                         int page,
                                         int size);
}

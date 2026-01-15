package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.portfolioservice.constants.PortfolioConstants;
import world.willfrog.alphafrogmicro.portfolioservice.domain.PortfolioPo;
import world.willfrog.alphafrogmicro.portfolioservice.domain.PortfolioTradePo;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PageResult;
import world.willfrog.alphafrogmicro.portfolioservice.dto.TradeCreateItem;
import world.willfrog.alphafrogmicro.portfolioservice.dto.TradeCreateRequest;
import world.willfrog.alphafrogmicro.portfolioservice.dto.TradeResponse;
import world.willfrog.alphafrogmicro.portfolioservice.exception.BizException;
import world.willfrog.alphafrogmicro.portfolioservice.mapper.PortfolioMapper;
import world.willfrog.alphafrogmicro.portfolioservice.mapper.TradeMapper;
import world.willfrog.alphafrogmicro.portfolioservice.service.TradeService;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class TradeServiceImpl implements TradeService {

    private static final Set<String> EVENT_TYPES = Set.of(
            "BUY", "SELL", "DIVIDEND_CASH", "DIVIDEND_STOCK",
            "SPLIT", "FEE", "CASH_IN", "CASH_OUT"
    );

    private final TradeMapper tradeMapper;
    private final PortfolioMapper portfolioMapper;

    public TradeServiceImpl(TradeMapper tradeMapper, PortfolioMapper portfolioMapper) {
        this.tradeMapper = tradeMapper;
        this.portfolioMapper = portfolioMapper;
    }

    @Override
    @Transactional
    public void createTrades(Long portfolioId, String userId, TradeCreateRequest request) {
        PortfolioPo po = portfolioMapper.findByIdAndUser(portfolioId, userId);
        if (po == null || !"active".equals(po.getStatus())) {
            throw new BizException(ResponseCode.DATA_NOT_FOUND, "组合不存在或已归档");
        }
        List<PortfolioTradePo> list = new ArrayList<>();
        for (TradeCreateItem item : request.getItems()) {
            validate(item);
            PortfolioTradePo trade = new PortfolioTradePo();
            trade.setPortfolioId(portfolioId);
            trade.setUserId(userId);
            trade.setSymbol(item.getSymbol());
            trade.setEventType(item.getEventType());
            trade.setQuantity(item.getQuantity());
            trade.setPrice(item.getPrice());
            trade.setFee(item.getFee());
            trade.setSlippage(item.getSlippage());
            trade.setTradeTime(item.getTradeTime());
            trade.setSettleDate(item.getSettleDate());
            trade.setNote(item.getNote());
            trade.setPayloadJson(StringUtils.defaultIfBlank(item.getPayloadJson(), "{}"));
            list.add(trade);
        }
        if (!list.isEmpty()) {
            tradeMapper.insertBatch(list);
        }
    }

    @Override
    public PageResult<TradeResponse> listTrades(Long portfolioId, String userId, OffsetDateTime from, OffsetDateTime to, String eventType, int page, int size) {
        if (StringUtils.isNotBlank(eventType) && !EVENT_TYPES.contains(eventType)) {
            throw new BizException(ResponseCode.PARAM_ERROR, "eventType 不合法");
        }
        int pageNum = Math.max(page, PortfolioConstants.DEFAULT_PAGE);
        int pageSize = Math.min(Math.max(size, 1), PortfolioConstants.MAX_PAGE_SIZE);
        int offset = (pageNum - 1) * pageSize;

        List<PortfolioTradePo> list = tradeMapper.list(portfolioId, userId, from, to, eventType, offset, pageSize);
        long total = tradeMapper.count(portfolioId, userId, from, to, eventType);

        List<TradeResponse> dtoList = list.stream().map(this::toResponse).toList();
        return PageResult.<TradeResponse>builder()
                .items(dtoList)
                .total(total)
                .page(pageNum)
                .size(pageSize)
                .build();
    }

    private void validate(TradeCreateItem item) {
        if (!EVENT_TYPES.contains(item.getEventType())) {
            throw new BizException(ResponseCode.PARAM_ERROR, "eventType 不合法");
        }
        if (item.getQuantity() != null && item.getQuantity().scale() > 2) {
            throw new BizException(ResponseCode.PARAM_ERROR, "quantity 最多两位小数");
        }
        if (item.getPrice() != null && item.getPrice().scale() > 2) {
            throw new BizException(ResponseCode.PARAM_ERROR, "price 最多两位小数");
        }
        if (item.getFee() != null && item.getFee().scale() > 2) {
            throw new BizException(ResponseCode.PARAM_ERROR, "fee 最多两位小数");
        }
    }

    private TradeResponse toResponse(PortfolioTradePo po) {
        return TradeResponse.builder()
                .id(po.getId())
                .portfolioId(po.getPortfolioId())
                .symbol(po.getSymbol())
                .eventType(po.getEventType())
                .quantity(po.getQuantity())
                .price(po.getPrice())
                .fee(po.getFee())
                .slippage(po.getSlippage())
                .tradeTime(po.getTradeTime())
                .settleDate(po.getSettleDate())
                .note(po.getNote())
                .build();
    }
}

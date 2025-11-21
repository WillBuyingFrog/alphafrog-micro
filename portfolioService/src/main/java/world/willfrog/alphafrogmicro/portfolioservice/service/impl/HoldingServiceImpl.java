package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.portfolioservice.domain.PortfolioHoldingPo;
import world.willfrog.alphafrogmicro.portfolioservice.domain.PortfolioPo;
import world.willfrog.alphafrogmicro.portfolioservice.dto.HoldingResponse;
import world.willfrog.alphafrogmicro.portfolioservice.dto.HoldingUpsertItem;
import world.willfrog.alphafrogmicro.portfolioservice.dto.HoldingUpsertRequest;
import world.willfrog.alphafrogmicro.portfolioservice.exception.BizException;
import world.willfrog.alphafrogmicro.portfolioservice.mapper.HoldingMapper;
import world.willfrog.alphafrogmicro.portfolioservice.mapper.PortfolioMapper;
import world.willfrog.alphafrogmicro.portfolioservice.service.HoldingService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class HoldingServiceImpl implements HoldingService {

    private static final Set<String> SYMBOL_TYPES = Set.of("stock", "etf", "index", "fund");
    private static final Set<String> POSITION_SIDES = Set.of("LONG", "SHORT");

    private final HoldingMapper holdingMapper;
    private final PortfolioMapper portfolioMapper;

    public HoldingServiceImpl(HoldingMapper holdingMapper, PortfolioMapper portfolioMapper) {
        this.holdingMapper = holdingMapper;
        this.portfolioMapper = portfolioMapper;
    }

    @Override
    @Transactional
    public List<HoldingResponse> upsertHoldings(Long portfolioId, String userId, HoldingUpsertRequest request) {
        PortfolioPo po = portfolioMapper.findByIdAndUser(portfolioId, userId);
        if (po == null || !"active".equals(po.getStatus())) {
            throw new BizException(ResponseCode.DATA_NOT_FOUND, "组合不存在或已归档");
        }

        List<PortfolioHoldingPo> list = new ArrayList<>();
        for (HoldingUpsertItem item : request.getItems()) {
            validateItem(item);
            PortfolioHoldingPo h = new PortfolioHoldingPo();
            h.setPortfolioId(portfolioId);
            h.setUserId(userId);
            h.setSymbol(item.getSymbol());
            h.setSymbolType(item.getSymbolType());
            h.setExchange(item.getExchange());
            h.setPositionSide(defaultSide(item.getPositionSide()));
            h.setQuantity(item.getQuantity());
            h.setAvgCost(item.getAvgCost());
            h.setExtJson("{}");
            list.add(h);
        }

        holdingMapper.deleteByPortfolioId(portfolioId, userId);
        if (!list.isEmpty()) {
            holdingMapper.insertBatch(list);
        }

        return listHoldings(portfolioId, userId);
    }

    @Override
    public List<HoldingResponse> listHoldings(Long portfolioId, String userId) {
        List<PortfolioHoldingPo> pos = holdingMapper.listByPortfolio(portfolioId, userId);
        return pos.stream().map(this::toResponse).toList();
    }

    private void validateItem(HoldingUpsertItem item) {
        if (!SYMBOL_TYPES.contains(item.getSymbolType())) {
            throw new BizException(ResponseCode.PARAM_ERROR, "symbolType 仅支持 stock/etf/index/fund");
        }
        if (item.getQuantity().scale() > 2 || item.getAvgCost().scale() > 2) {
            throw new BizException(ResponseCode.PARAM_ERROR, "数量/成本最多两位小数");
        }
    }

    private String defaultSide(String side) {
        String value = StringUtils.defaultIfBlank(side, "LONG");
        if (!POSITION_SIDES.contains(value)) {
            throw new BizException(ResponseCode.PARAM_ERROR, "positionSide 仅支持 LONG/SHORT");
        }
        return value;
    }

    private HoldingResponse toResponse(PortfolioHoldingPo po) {
        return HoldingResponse.builder()
                .id(po.getId())
                .portfolioId(po.getPortfolioId())
                .symbol(po.getSymbol())
                .symbolType(po.getSymbolType())
                .exchange(po.getExchange())
                .positionSide(po.getPositionSide())
                .quantity(po.getQuantity())
                .avgCost(po.getAvgCost())
                .updatedAt(po.getUpdatedAt())
                .build();
    }
}

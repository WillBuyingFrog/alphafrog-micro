package world.willfrog.alphafrogmicro.portfolioservice.util;

import lombok.experimental.UtilityClass;
import world.willfrog.alphafrogmicro.portfolioservice.dto.HoldingResponse;
import world.willfrog.alphafrogmicro.portfolioservice.dto.ValuationResponse;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@UtilityClass
public class ValuationMapper {

    /**
        提示：当前实现用持仓均价作为“价格”占位，pnl 为 0。
        后续接入行情服务时，请替换 lastPrice 与 pnl 计算。
     */
    public static ValuationResponse mockValuation(List<HoldingResponse> holdings) {
        BigDecimal total = BigDecimal.ZERO;
        List<ValuationResponse.ValuationPosition> positions = new ArrayList<>();
        for (HoldingResponse h : holdings) {
            BigDecimal lastPrice = h.getAvgCost() == null ? BigDecimal.ZERO : h.getAvgCost();
            BigDecimal mv = lastPrice.multiply(h.getQuantity() == null ? BigDecimal.ZERO : h.getQuantity());
            total = total.add(mv);
            positions.add(ValuationResponse.ValuationPosition.builder()
                    .symbol(h.getSymbol())
                    .symbolType(h.getSymbolType())
                    .quantity(h.getQuantity())
                    .lastPrice(lastPrice)
                    .marketValue(mv)
                    .build());
        }
        return ValuationResponse.builder()
                .totalValue(total)
                .pnlAbs(BigDecimal.ZERO)
                .pnlPct(BigDecimal.ZERO)
                .positions(positions)
                .build();
    }
}

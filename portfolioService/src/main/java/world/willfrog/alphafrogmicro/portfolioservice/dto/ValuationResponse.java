package world.willfrog.alphafrogmicro.portfolioservice.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class ValuationResponse {
    private BigDecimal totalValue;
    private BigDecimal pnlAbs;
    private BigDecimal pnlPct;
    private List<ValuationPosition> positions;

    @Data
    @Builder
    public static class ValuationPosition {
        private String symbol;
        private String symbolType;
        private BigDecimal quantity;
        private BigDecimal lastPrice;
        private BigDecimal marketValue;
    }
}

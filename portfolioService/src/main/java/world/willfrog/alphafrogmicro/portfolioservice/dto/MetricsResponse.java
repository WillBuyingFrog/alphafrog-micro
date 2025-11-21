package world.willfrog.alphafrogmicro.portfolioservice.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class MetricsResponse {
    private BigDecimal returnPct;
    private BigDecimal volatility;
    private BigDecimal maxDrawdown;
    private String fromDate;
    private String toDate;
    private String note;
}

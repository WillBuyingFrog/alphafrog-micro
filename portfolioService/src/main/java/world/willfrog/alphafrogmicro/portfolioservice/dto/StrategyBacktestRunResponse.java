package world.willfrog.alphafrogmicro.portfolioservice.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@Builder
public class StrategyBacktestRunResponse {
    private Long id;
    private Long strategyId;
    private OffsetDateTime runTime;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
}

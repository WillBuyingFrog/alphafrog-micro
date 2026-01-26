package world.willfrog.alphafrogmicro.portfolioservice.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class StrategyBacktestRunCreateRequest {
    private LocalDate startDate;
    private LocalDate endDate;

    @Size(max = 8000)
    private String paramsJson;
}

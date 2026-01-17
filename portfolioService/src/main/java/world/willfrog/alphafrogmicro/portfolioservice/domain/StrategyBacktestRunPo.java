package world.willfrog.alphafrogmicro.portfolioservice.domain;

import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class StrategyBacktestRunPo {
    private Long id;
    private Long strategyId;
    private String userId;
    private OffsetDateTime runTime;
    private LocalDate startDate;
    private LocalDate endDate;
    private String paramsJson;
    private String status;
    private OffsetDateTime queuedAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private String extJson;
}

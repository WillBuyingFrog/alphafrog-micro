package world.willfrog.alphafrogmicro.portfolioservice.service;

import world.willfrog.alphafrogmicro.portfolioservice.dto.*;

import java.time.LocalDate;
import java.util.List;

public interface StrategyService {

    StrategyResponse create(String userId, StrategyCreateRequest request);

    PageResult<StrategyResponse> list(String userId, String status, String keyword, int page, int size);

    StrategyResponse getById(Long id, String userId);

    StrategyResponse update(Long id, String userId, StrategyUpdateRequest request);

    void archive(Long id, String userId);

    List<StrategyTargetResponse> upsertTargets(Long strategyId, String userId, StrategyTargetUpsertRequest request);

    List<StrategyTargetResponse> listTargets(Long strategyId, String userId);

    StrategyBacktestRunResponse createBacktestRun(Long strategyId, String userId, StrategyBacktestRunCreateRequest request);

    PageResult<StrategyBacktestRunResponse> listBacktestRuns(Long strategyId,
                                                             String userId,
                                                             String status,
                                                             int page,
                                                             int size);

    PageResult<StrategyNavResponse> listNav(Long strategyId,
                                            Long runId,
                                            String userId,
                                            LocalDate from,
                                            LocalDate to,
                                            int page,
                                            int size);
}

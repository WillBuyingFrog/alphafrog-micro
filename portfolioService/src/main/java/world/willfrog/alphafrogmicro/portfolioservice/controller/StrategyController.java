package world.willfrog.alphafrogmicro.portfolioservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.portfolioservice.dto.*;
import world.willfrog.alphafrogmicro.portfolioservice.service.StrategyService;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/strategies")
public class StrategyController {

    private final StrategyService strategyService;

    public StrategyController(StrategyService strategyService) {
        this.strategyService = strategyService;
    }

    @PostMapping
    public ResponseWrapper<StrategyResponse> create(
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody StrategyCreateRequest request) {
        return ResponseWrapper.success(strategyService.create(userId, request));
    }

    @GetMapping
    public ResponseWrapper<PageResult<StrategyResponse>> list(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseWrapper.success(strategyService.list(userId, status, keyword, page, size));
    }

    @GetMapping("/{id}")
    public ResponseWrapper<StrategyResponse> getById(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long id) {
        return ResponseWrapper.success(strategyService.getById(id, userId));
    }

    @PatchMapping("/{id}")
    public ResponseWrapper<StrategyResponse> update(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long id,
            @Valid @RequestBody StrategyUpdateRequest request) {
        return ResponseWrapper.success(strategyService.update(id, userId, request));
    }

    @DeleteMapping("/{id}")
    public ResponseWrapper<Void> archive(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long id) {
        strategyService.archive(id, userId);
        return ResponseWrapper.success(null);
    }

    @PostMapping("/{id}/targets:bulk-upsert")
    public ResponseWrapper<List<StrategyTargetResponse>> upsertTargets(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long strategyId,
            @Valid @RequestBody StrategyTargetUpsertRequest request) {
        return ResponseWrapper.success(strategyService.upsertTargets(strategyId, userId, request));
    }

    @GetMapping("/{id}/targets")
    public ResponseWrapper<List<StrategyTargetResponse>> listTargets(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long strategyId) {
        return ResponseWrapper.success(strategyService.listTargets(strategyId, userId));
    }

    @PostMapping("/{id}/backtests")
    public ResponseWrapper<StrategyBacktestRunResponse> createBacktestRun(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long strategyId,
            @Valid @RequestBody StrategyBacktestRunCreateRequest request) {
        return ResponseWrapper.success(strategyService.createBacktestRun(strategyId, userId, request));
    }

    @GetMapping("/{id}/backtests")
    public ResponseWrapper<PageResult<StrategyBacktestRunResponse>> listBacktestRuns(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long strategyId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseWrapper.success(strategyService.listBacktestRuns(strategyId, userId, status, page, size));
    }

    @GetMapping("/{id}/backtests/{runId}/nav")
    public ResponseWrapper<PageResult<StrategyNavResponse>> listNav(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long strategyId,
            @PathVariable("runId") Long runId,
            @RequestParam(value = "from", required = false) LocalDate from,
            @RequestParam(value = "to", required = false) LocalDate to,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "200") int size) {
        return ResponseWrapper.success(strategyService.listNav(strategyId, runId, userId, from, to, page, size));
    }
}

package world.willfrog.alphafrogmicro.portfolioservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.portfolioservice.dto.*;
import world.willfrog.alphafrogmicro.portfolioservice.service.HoldingService;
import world.willfrog.alphafrogmicro.portfolioservice.service.PortfolioService;
import world.willfrog.alphafrogmicro.portfolioservice.service.TradeService;
import world.willfrog.alphafrogmicro.portfolioservice.util.ValuationMapper;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/portfolios")
public class PortfolioController {

    private final PortfolioService portfolioService;
    private final HoldingService holdingService;
    private final TradeService tradeService;

    public PortfolioController(PortfolioService portfolioService,
                               HoldingService holdingService,
                               TradeService tradeService) {
        this.portfolioService = portfolioService;
        this.holdingService = holdingService;
        this.tradeService = tradeService;
    }
    @PostMapping
    public ResponseWrapper<PortfolioResponse> create(
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody PortfolioCreateRequest request) {
        return ResponseWrapper.success(portfolioService.create(userId, request));
    }

    @GetMapping
    public ResponseWrapper<PageResult<PortfolioResponse>> list(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseWrapper.success(portfolioService.list(userId, status, keyword, page, size));
    }

    @GetMapping("/{id}")
    public ResponseWrapper<PortfolioResponse> getById(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long id) {
        return ResponseWrapper.success(portfolioService.getById(id, userId));
    }

    @PatchMapping("/{id}")
    public ResponseWrapper<PortfolioResponse> update(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long id,
            @Valid @RequestBody PortfolioUpdateRequest request) {
        return ResponseWrapper.success(portfolioService.update(id, userId, request));
    }

    @DeleteMapping("/{id}")
    public ResponseWrapper<Void> archive(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long id) {
        portfolioService.archive(id, userId);
        return ResponseWrapper.success(null);
    }

    @PostMapping("/{id}/holdings:bulk-upsert")
    public ResponseWrapper<List<HoldingResponse>> upsertHoldings(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long portfolioId,
            @Valid @RequestBody HoldingUpsertRequest request) {
        return ResponseWrapper.success(holdingService.upsertHoldings(portfolioId, userId, request));
    }

    @GetMapping("/{id}/holdings")
    public ResponseWrapper<List<HoldingResponse>> listHoldings(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long portfolioId) {
        return ResponseWrapper.success(holdingService.listHoldings(portfolioId, userId));
    }

    @PostMapping("/{id}/trades")
    public ResponseWrapper<Void> createTrades(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long portfolioId,
            @Valid @RequestBody TradeCreateRequest request) {
        tradeService.createTrades(portfolioId, userId, request);
        return ResponseWrapper.success(null);
    }

    @GetMapping("/{id}/trades")
    public ResponseWrapper<PageResult<TradeResponse>> listTrades(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long portfolioId,
            @RequestParam(value = "from", required = false) OffsetDateTime from,
            @RequestParam(value = "to", required = false) OffsetDateTime to,
            @RequestParam(value = "event_type", required = false) String eventType,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseWrapper.success(tradeService.listTrades(portfolioId, userId, from, to, eventType, page, size));
    }

    @GetMapping("/{id}/valuation")
    public ResponseWrapper<ValuationResponse> valuation(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long portfolioId) {
        List<HoldingResponse> holdings = holdingService.listHoldings(portfolioId, userId);
        ValuationResponse response = ValuationMapper.mockValuation(holdings);
        return ResponseWrapper.success(response);
    }

    @GetMapping("/{id}/metrics")
    public ResponseWrapper<MetricsResponse> metrics(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long portfolioId,
            @RequestParam("from") String from,
            @RequestParam("to") String to) {
        MetricsResponse resp = MetricsResponse.builder()
                .returnPct(java.math.BigDecimal.ZERO)
                .volatility(java.math.BigDecimal.ZERO)
                .maxDrawdown(java.math.BigDecimal.ZERO)
                .fromDate(from)
                .toDate(to)
                .note("占位实现，后续接入行情计算")
                .build();
        return ResponseWrapper.success(resp);
    }

    @GetMapping("/health")
    public ResponseWrapper<String> health() {
        return ResponseWrapper.success("ok", ResponseCode.SUCCESS.getMessage());
    }
}

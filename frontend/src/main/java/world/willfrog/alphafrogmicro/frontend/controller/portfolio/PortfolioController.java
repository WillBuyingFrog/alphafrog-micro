package world.willfrog.alphafrogmicro.frontend.controller.portfolio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.common.dto.compact.CompactApiResponse;
import world.willfrog.alphafrogmicro.common.dto.compact.CompactMeta;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.common.utils.compact.CompactJsonConverter;
import world.willfrog.alphafrogmicro.portfolio.idl.ArchivePortfolioRequest;
import world.willfrog.alphafrogmicro.portfolio.idl.CreatePortfolioRequest;
import world.willfrog.alphafrogmicro.portfolio.idl.GetPortfolioRequest;
import world.willfrog.alphafrogmicro.portfolio.idl.HoldingMessage;
import world.willfrog.alphafrogmicro.portfolio.idl.HoldingsBulkUpsertRequest;
import world.willfrog.alphafrogmicro.portfolio.idl.HoldingsListRequest;
import world.willfrog.alphafrogmicro.portfolio.idl.HoldingsListResponse;
import world.willfrog.alphafrogmicro.portfolio.idl.ListPortfolioRequest;
import world.willfrog.alphafrogmicro.portfolio.idl.ListPortfolioResponse;
import world.willfrog.alphafrogmicro.portfolio.idl.MetricsRequest;
import world.willfrog.alphafrogmicro.portfolio.idl.PortfolioDubboService;
import world.willfrog.alphafrogmicro.portfolio.idl.PortfolioMessage;
import world.willfrog.alphafrogmicro.portfolio.idl.TradeMessage;
import world.willfrog.alphafrogmicro.portfolio.idl.TradesCreateRequest;
import world.willfrog.alphafrogmicro.portfolio.idl.TradesListRequest;
import world.willfrog.alphafrogmicro.portfolio.idl.TradesListResponse;
import world.willfrog.alphafrogmicro.portfolio.idl.UpdatePortfolioRequest;
import world.willfrog.alphafrogmicro.portfolio.idl.ValuationPositionMessage;
import world.willfrog.alphafrogmicro.portfolio.idl.ValuationRequest;
import world.willfrog.alphafrogmicro.portfolioservice.dto.HoldingResponse;
import world.willfrog.alphafrogmicro.portfolioservice.dto.HoldingUpsertItem;
import world.willfrog.alphafrogmicro.portfolioservice.dto.HoldingUpsertRequest;
import world.willfrog.alphafrogmicro.portfolioservice.dto.MetricsResponse;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PageResult;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PortfolioCreateRequest;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PortfolioResponse;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PortfolioUpdateRequest;
import world.willfrog.alphafrogmicro.portfolioservice.dto.TradeCreateItem;
import world.willfrog.alphafrogmicro.portfolioservice.dto.TradeCreateRequest;
import world.willfrog.alphafrogmicro.portfolioservice.dto.TradeResponse;
import world.willfrog.alphafrogmicro.portfolioservice.dto.ValuationResponse;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/portfolios")
@RequiredArgsConstructor
@Slf4j
public class PortfolioController {

    @DubboReference
    private PortfolioDubboService portfolioDubboService;

    private final AuthService authService;

    @PostMapping
    public ResponseWrapper<PortfolioResponse> create(Authentication authentication,
                                                     @RequestBody PortfolioCreateRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            PortfolioMessage response = portfolioDubboService.createPortfolio(toCreateRequest(userId, request));
            return ResponseWrapper.success(toPortfolioResponse(response));
        } catch (RpcException e) {
            return handleRpcError(e, "创建组合");
        } catch (Exception e) {
            return handleError(e, "创建组合");
        }
    }

    @GetMapping
    public ResponseWrapper<?> list(Authentication authentication,
                                   @RequestParam(value = "status", required = false) String status,
                                   @RequestParam(value = "keyword", required = false) String keyword,
                                   @RequestParam(value = "page", defaultValue = "1") int page,
                                   @RequestParam(value = "size", defaultValue = "20") int size,
                                   @RequestParam(value = "format", required = false, defaultValue = "compact") String format) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            ListPortfolioRequest req = ListPortfolioRequest.newBuilder()
                    .setUserId(userId)
                    .setStatus(nvl(status))
                    .setKeyword(nvl(keyword))
                    .setPage(page)
                    .setSize(size)
                    .build();
            ListPortfolioResponse response = portfolioDubboService.listPortfolio(req);
            if (isCompact(format)) {
                CompactMeta meta = CompactJsonConverter.extractPageMetaFromResponse(response, response.getPage(), response.getSize());
                CompactApiResponse compact = CompactJsonConverter.convert(response, null, meta);
                return ResponseWrapper.success(compact);
            }
            return ResponseWrapper.success(toPortfolioPage(response));
        } catch (RpcException e) {
            return handleRpcError(e, "查询组合列表");
        } catch (Exception e) {
            return handleError(e, "查询组合列表");
        }
    }

    @GetMapping("/{id}")
    public ResponseWrapper<PortfolioResponse> getById(Authentication authentication,
                                                      @PathVariable("id") Long id) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            GetPortfolioRequest req = GetPortfolioRequest.newBuilder()
                    .setUserId(userId)
                    .setId(id)
                    .build();
            PortfolioMessage response = portfolioDubboService.getPortfolio(req);
            return ResponseWrapper.success(toPortfolioResponse(response));
        } catch (RpcException e) {
            return handleRpcError(e, "查询组合");
        } catch (Exception e) {
            return handleError(e, "查询组合");
        }
    }

    @PatchMapping("/{id}")
    public ResponseWrapper<PortfolioResponse> update(Authentication authentication,
                                                     @PathVariable("id") Long id,
                                                     @RequestBody PortfolioUpdateRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            UpdatePortfolioRequest req = toUpdateRequest(userId, id, request);
            PortfolioMessage response = portfolioDubboService.updatePortfolio(req);
            return ResponseWrapper.success(toPortfolioResponse(response));
        } catch (RpcException e) {
            return handleRpcError(e, "更新组合");
        } catch (Exception e) {
            return handleError(e, "更新组合");
        }
    }

    @DeleteMapping("/{id}")
    public ResponseWrapper<Void> archive(Authentication authentication,
                                         @PathVariable("id") Long id) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            ArchivePortfolioRequest req = ArchivePortfolioRequest.newBuilder()
                    .setUserId(userId)
                    .setId(id)
                    .build();
            portfolioDubboService.archivePortfolio(req);
            return ResponseWrapper.success(null);
        } catch (RpcException e) {
            return handleRpcError(e, "归档组合");
        } catch (Exception e) {
            return handleError(e, "归档组合");
        }
    }

    @PostMapping("/{id}/holdings:bulk-upsert")
    public ResponseWrapper<List<HoldingResponse>> upsertHoldings(Authentication authentication,
                                                                 @PathVariable("id") Long portfolioId,
                                                                 @RequestBody HoldingUpsertRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            return ResponseWrapper.error(ResponseCode.PARAM_ERROR, "items 不能为空");
        }
        try {
            HoldingsBulkUpsertRequest req = toHoldingsUpsertRequest(userId, portfolioId, request);
            HoldingsListResponse response = portfolioDubboService.holdingsBulkUpsert(req);
            return ResponseWrapper.success(toHoldingList(response));
        } catch (RpcException e) {
            return handleRpcError(e, "更新持仓");
        } catch (Exception e) {
            return handleError(e, "更新持仓");
        }
    }

    @GetMapping("/{id}/holdings")
    public ResponseWrapper<?> listHoldings(Authentication authentication,
                                           @PathVariable("id") Long portfolioId,
                                           @RequestParam(value = "format", required = false, defaultValue = "compact") String format) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            HoldingsListRequest req = HoldingsListRequest.newBuilder()
                    .setUserId(userId)
                    .setPortfolioId(portfolioId)
                    .build();
            HoldingsListResponse response = portfolioDubboService.holdingsList(req);
            if (isCompact(format)) {
                CompactApiResponse compact = CompactJsonConverter.convert(response, null, null);
                return ResponseWrapper.success(compact);
            }
            return ResponseWrapper.success(toHoldingList(response));
        } catch (RpcException e) {
            return handleRpcError(e, "查询持仓");
        } catch (Exception e) {
            return handleError(e, "查询持仓");
        }
    }

    @PostMapping("/{id}/trades")
    public ResponseWrapper<Void> createTrades(Authentication authentication,
                                              @PathVariable("id") Long portfolioId,
                                              @RequestBody TradeCreateRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            return ResponseWrapper.error(ResponseCode.PARAM_ERROR, "items 不能为空");
        }
        try {
            TradesCreateRequest req = toTradesCreateRequest(userId, portfolioId, request);
            portfolioDubboService.tradesCreate(req);
            return ResponseWrapper.success(null);
        } catch (RpcException e) {
            return handleRpcError(e, "新增交易流水");
        } catch (Exception e) {
            return handleError(e, "新增交易流水");
        }
    }

    @GetMapping("/{id}/trades")
    public ResponseWrapper<?> listTrades(Authentication authentication,
                                         @PathVariable("id") Long portfolioId,
                                         @RequestParam(value = "from", required = false) String from,
                                         @RequestParam(value = "to", required = false) String to,
                                         @RequestParam(value = "event_type", required = false) String eventType,
                                         @RequestParam(value = "page", defaultValue = "1") int page,
                                         @RequestParam(value = "size", defaultValue = "20") int size,
                                         @RequestParam(value = "format", required = false, defaultValue = "compact") String format) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            TradesListRequest req = TradesListRequest.newBuilder()
                    .setUserId(userId)
                    .setPortfolioId(portfolioId)
                    .setFrom(nvl(from))
                    .setTo(nvl(to))
                    .setEventType(nvl(eventType))
                    .setPage(page)
                    .setSize(size)
                    .build();
            TradesListResponse response = portfolioDubboService.tradesList(req);
            if (isCompact(format)) {
                CompactMeta meta = CompactJsonConverter.extractPageMetaFromResponse(response, response.getPage(), response.getSize());
                CompactApiResponse compact = CompactJsonConverter.convert(response, null, meta);
                return ResponseWrapper.success(compact);
            }
            return ResponseWrapper.success(toTradePage(response));
        } catch (RpcException e) {
            return handleRpcError(e, "查询交易流水");
        } catch (Exception e) {
            return handleError(e, "查询交易流水");
        }
    }

    @GetMapping("/{id}/valuation")
    public ResponseWrapper<ValuationResponse> valuation(Authentication authentication,
                                                        @PathVariable("id") Long portfolioId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            ValuationRequest req = ValuationRequest.newBuilder()
                    .setUserId(userId)
                    .setPortfolioId(portfolioId)
                    .build();
            world.willfrog.alphafrogmicro.portfolio.idl.ValuationResponse response = portfolioDubboService.valuation(req);
            return ResponseWrapper.success(toValuationResponse(response));
        } catch (RpcException e) {
            return handleRpcError(e, "估值");
        } catch (Exception e) {
            return handleError(e, "估值");
        }
    }

    @GetMapping("/{id}/metrics")
    public ResponseWrapper<MetricsResponse> metrics(Authentication authentication,
                                                    @PathVariable("id") Long portfolioId,
                                                    @RequestParam("from") String from,
                                                    @RequestParam("to") String to) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            MetricsRequest req = MetricsRequest.newBuilder()
                    .setUserId(userId)
                    .setPortfolioId(portfolioId)
                    .setFrom(nvl(from))
                    .setTo(nvl(to))
                    .build();
            world.willfrog.alphafrogmicro.portfolio.idl.MetricsResponse response = portfolioDubboService.metrics(req);
            return ResponseWrapper.success(toMetricsResponse(response));
        } catch (RpcException e) {
            return handleRpcError(e, "收益指标");
        } catch (Exception e) {
            return handleError(e, "收益指标");
        }
    }

    private CreatePortfolioRequest toCreateRequest(String userId, PortfolioCreateRequest request) {
        CreatePortfolioRequest.Builder builder = CreatePortfolioRequest.newBuilder()
                .setUserId(userId)
                .setName(nvl(request.getName()))
                .setVisibility(nvl(request.getVisibility()))
                .setTimezone(nvl(request.getTimezone()))
                .setPortfolioType(nvl(request.getPortfolioType()))
                .setBaseCurrency(nvl(request.getBaseCurrency()))
                .setBenchmarkSymbol(nvl(request.getBenchmarkSymbol()));
        if (request.getTags() != null) {
            builder.addAllTags(request.getTags());
        }
        return builder.build();
    }

    private UpdatePortfolioRequest toUpdateRequest(String userId, Long id, PortfolioUpdateRequest request) {
        UpdatePortfolioRequest.Builder builder = UpdatePortfolioRequest.newBuilder()
                .setUserId(userId)
                .setId(id)
                .setName(nvl(request.getName()))
                .setVisibility(nvl(request.getVisibility()))
                .setStatus(nvl(request.getStatus()))
                .setPortfolioType(nvl(request.getPortfolioType()))
                .setBaseCurrency(nvl(request.getBaseCurrency()))
                .setBenchmarkSymbol(nvl(request.getBenchmarkSymbol()));
        if (request.getTags() != null) {
            builder.addAllTags(request.getTags());
            builder.setTagsPresent(true);
        }
        return builder.build();
    }

    private HoldingsBulkUpsertRequest toHoldingsUpsertRequest(String userId, Long portfolioId, HoldingUpsertRequest request) {
        HoldingsBulkUpsertRequest.Builder builder = HoldingsBulkUpsertRequest.newBuilder()
                .setUserId(userId)
                .setPortfolioId(portfolioId);
        if (request.getItems() != null) {
            for (HoldingUpsertItem item : request.getItems()) {
                builder.addItems(HoldingMessage.newBuilder()
                        .setSymbol(nvl(item.getSymbol()))
                        .setSymbolType(nvl(item.getSymbolType()))
                        .setExchange(nvl(item.getExchange()))
                        .setPositionSide(nvl(item.getPositionSide()))
                        .setQuantity(toStr(item.getQuantity()))
                        .setAvgCost(toStr(item.getAvgCost()))
                        .build());
            }
        }
        return builder.build();
    }

    private TradesCreateRequest toTradesCreateRequest(String userId, Long portfolioId, TradeCreateRequest request) {
        TradesCreateRequest.Builder builder = TradesCreateRequest.newBuilder()
                .setUserId(userId)
                .setPortfolioId(portfolioId);
        if (request.getItems() != null) {
            for (TradeCreateItem item : request.getItems()) {
                builder.addItems(TradeMessage.newBuilder()
                        .setSymbol(nvl(item.getSymbol()))
                        .setEventType(nvl(item.getEventType()))
                        .setQuantity(toStr(item.getQuantity()))
                        .setPrice(toStr(item.getPrice()))
                        .setFee(toStr(item.getFee()))
                        .setSlippage(toStr(item.getSlippage()))
                        .setTradeTime(item.getTradeTime() != null ? item.getTradeTime().toString() : "")
                        .setSettleDate(item.getSettleDate() != null ? item.getSettleDate().toString() : "")
                        .setNote(nvl(item.getNote()))
                        .setPayloadJson(nvl(item.getPayloadJson()))
                        .build());
            }
        }
        return builder.build();
    }

    private PageResult<PortfolioResponse> toPortfolioPage(ListPortfolioResponse response) {
        List<PortfolioResponse> items = new ArrayList<>();
        for (PortfolioMessage msg : response.getItemsList()) {
            items.add(toPortfolioResponse(msg));
        }
        return PageResult.<PortfolioResponse>builder()
                .items(items)
                .total(response.getTotal())
                .page(response.getPage())
                .size(response.getSize())
                .build();
    }

    private PortfolioResponse toPortfolioResponse(PortfolioMessage msg) {
        return PortfolioResponse.builder()
                .id(msg.getId())
                .userId(emptyToNull(msg.getUserId()))
                .name(emptyToNull(msg.getName()))
                .visibility(emptyToNull(msg.getVisibility()))
                .tags(msg.getTagsList())
                .portfolioType(emptyToNull(msg.getPortfolioType()))
                .baseCurrency(emptyToNull(msg.getBaseCurrency()))
                .benchmarkSymbol(emptyToNull(msg.getBenchmarkSymbol()))
                .status(emptyToNull(msg.getStatus()))
                .timezone(emptyToNull(msg.getTimezone()))
                .createdAt(parseTime(msg.getCreatedAt()))
                .updatedAt(parseTime(msg.getUpdatedAt()))
                .build();
    }

    private List<HoldingResponse> toHoldingList(HoldingsListResponse response) {
        List<HoldingResponse> list = new ArrayList<>();
        for (HoldingMessage msg : response.getItemsList()) {
            list.add(HoldingResponse.builder()
                    .id(msg.getId())
                    .portfolioId(msg.getPortfolioId())
                    .symbol(emptyToNull(msg.getSymbol()))
                    .symbolType(emptyToNull(msg.getSymbolType()))
                    .exchange(emptyToNull(msg.getExchange()))
                    .positionSide(emptyToNull(msg.getPositionSide()))
                    .quantity(toDecimal(msg.getQuantity()))
                    .avgCost(toDecimal(msg.getAvgCost()))
                    .updatedAt(parseTime(msg.getUpdatedAt()))
                    .build());
        }
        return list;
    }

    private PageResult<TradeResponse> toTradePage(TradesListResponse response) {
        List<TradeResponse> list = new ArrayList<>();
        for (TradeMessage msg : response.getItemsList()) {
            list.add(toTradeResponse(msg));
        }
        return PageResult.<TradeResponse>builder()
                .items(list)
                .total(response.getTotal())
                .page(response.getPage())
                .size(response.getSize())
                .build();
    }

    private TradeResponse toTradeResponse(TradeMessage msg) {
        return TradeResponse.builder()
                .id(msg.getId())
                .portfolioId(msg.getPortfolioId())
                .symbol(emptyToNull(msg.getSymbol()))
                .eventType(emptyToNull(msg.getEventType()))
                .quantity(toDecimal(msg.getQuantity()))
                .price(toDecimal(msg.getPrice()))
                .fee(toDecimal(msg.getFee()))
                .slippage(toDecimal(msg.getSlippage()))
                .tradeTime(parseTime(msg.getTradeTime()))
                .settleDate(parseTime(msg.getSettleDate()))
                .note(emptyToNull(msg.getNote()))
                .build();
    }

    private ValuationResponse toValuationResponse(world.willfrog.alphafrogmicro.portfolio.idl.ValuationResponse response) {
        List<ValuationResponse.ValuationPosition> positions = new ArrayList<>();
        for (ValuationPositionMessage position : response.getPositionsList()) {
            positions.add(ValuationResponse.ValuationPosition.builder()
                    .symbol(emptyToNull(position.getSymbol()))
                    .symbolType(emptyToNull(position.getSymbolType()))
                    .quantity(toDecimal(position.getQuantity()))
                    .lastPrice(toDecimal(position.getLastPrice()))
                    .marketValue(toDecimal(position.getMarketValue()))
                    .build());
        }
        return ValuationResponse.builder()
                .totalValue(toDecimal(response.getTotalValue()))
                .pnlAbs(toDecimal(response.getPnlAbs()))
                .pnlPct(toDecimal(response.getPnlPct()))
                .positions(positions)
                .build();
    }

    private MetricsResponse toMetricsResponse(world.willfrog.alphafrogmicro.portfolio.idl.MetricsResponse response) {
        return MetricsResponse.builder()
                .returnPct(toDecimal(response.getReturnPct()))
                .volatility(toDecimal(response.getVolatility()))
                .maxDrawdown(toDecimal(response.getMaxDrawdown()))
                .fromDate(emptyToNull(response.getFrom()))
                .toDate(emptyToNull(response.getTo()))
                .note(emptyToNull(response.getNote()))
                .build();
    }

    private String resolveUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        String username = authentication.getName();
        User user = authService.getUserByUsername(username);
        if (user == null || user.getUserId() == null) {
            return null;
        }
        return String.valueOf(user.getUserId());
    }

    private <T> ResponseWrapper<T> handleRpcError(RpcException e, String action) {
        log.error("{}失败: {}", action, e.getMessage());
        return ResponseWrapper.error(ResponseCode.EXTERNAL_SERVICE_ERROR, action + "失败");
    }

    private <T> ResponseWrapper<T> handleError(Exception e, String action) {
        log.error("{}失败", action, e);
        return ResponseWrapper.error(ResponseCode.SYSTEM_ERROR, action + "失败");
    }

    private boolean isCompact(String format) {
        return "compact".equalsIgnoreCase(format);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private BigDecimal toDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return new BigDecimal(value);
    }

    private String toStr(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private OffsetDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(value);
    }
}

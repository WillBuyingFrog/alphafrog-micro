package world.willfrog.alphafrogmicro.portfolioservice;

import org.apache.dubbo.config.annotation.DubboService;
import world.willfrog.alphafrogmicro.portfolio.idl.*;
import world.willfrog.alphafrogmicro.portfolioservice.dto.*;
import world.willfrog.alphafrogmicro.portfolioservice.service.HoldingService;
import world.willfrog.alphafrogmicro.portfolioservice.service.PortfolioService;
import world.willfrog.alphafrogmicro.portfolioservice.service.TradeService;
import world.willfrog.alphafrogmicro.portfolioservice.util.ValuationMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Dubbo gRPC 入口，面向 frontend。
 */
@DubboService
public class PortfolioDubboServiceImpl extends DubboPortfolioDubboServiceTriple.PortfolioDubboServiceImplBase {

    private final PortfolioService portfolioService;
    private final HoldingService holdingService;
    private final TradeService tradeService;

    public PortfolioDubboServiceImpl(PortfolioService portfolioService,
                                     HoldingService holdingService,
                                     TradeService tradeService) {
        this.portfolioService = portfolioService;
        this.holdingService = holdingService;
        this.tradeService = tradeService;
    }

    @Override
    public PortfolioMessage createPortfolio(CreatePortfolioRequest request) {
        PortfolioCreateRequest dto = new PortfolioCreateRequest();
        dto.setName(request.getName());
        dto.setVisibility(request.getVisibility());
        dto.setTags(request.getTagsList());
        dto.setTimezone(request.getTimezone());
        return toProto(portfolioService.create(request.getUserId(), dto));
    }

    @Override
    public PortfolioMessage updatePortfolio(UpdatePortfolioRequest request) {
        PortfolioUpdateRequest dto = new PortfolioUpdateRequest();
        dto.setName(request.getName());
        dto.setVisibility(request.getVisibility());
        dto.setTags(request.getTagsList());
        dto.setStatus(request.getStatus());
        return toProto(portfolioService.update(request.getId(), request.getUserId(), dto));
    }

    @Override
    public PortfolioEmpty archivePortfolio(ArchivePortfolioRequest request) {
        portfolioService.archive(request.getId(), request.getUserId());
        return PortfolioEmpty.newBuilder().build();
    }

    @Override
    public PortfolioMessage getPortfolio(GetPortfolioRequest request) {
        return toProto(portfolioService.getById(request.getId(), request.getUserId()));
    }

    @Override
    public ListPortfolioResponse listPortfolio(ListPortfolioRequest request) {
        PageResult<PortfolioResponse> page = portfolioService.list(
                request.getUserId(),
                request.getStatus(),
                request.getKeyword(),
                request.getPage(),
                request.getSize()
        );
        ListPortfolioResponse.Builder b = ListPortfolioResponse.newBuilder()
                .setTotal(page.getTotal())
                .setPage(page.getPage())
                .setSize(page.getSize());
        page.getItems().forEach(item -> b.addItems(toProto(item)));
        return b.build();
    }

    @Override
    public HoldingsListResponse holdingsBulkUpsert(HoldingsBulkUpsertRequest request) {
        HoldingUpsertRequest dto = new HoldingUpsertRequest();
        dto.setItems(request.getItemsList().stream().map(this::toHoldingItem).collect(Collectors.toList()));
        List<HoldingResponse> list = holdingService.upsertHoldings(request.getPortfolioId(), request.getUserId(), dto);
        return toHoldingsProto(list);
    }

    @Override
    public HoldingsListResponse holdingsList(HoldingsListRequest request) {
        return toHoldingsProto(holdingService.listHoldings(request.getPortfolioId(), request.getUserId()));
    }

    @Override
    public PortfolioEmpty tradesCreate(TradesCreateRequest request) {
        TradeCreateRequest dto = new TradeCreateRequest();
        dto.setItems(request.getItemsList().stream().map(this::toTradeItem).collect(Collectors.toList()));
        tradeService.createTrades(request.getPortfolioId(), request.getUserId(), dto);
        return PortfolioEmpty.newBuilder().build();
    }

    @Override
    public TradesListResponse tradesList(TradesListRequest request) {
        PageResult<TradeResponse> page = tradeService.listTrades(
                request.getPortfolioId(),
                request.getUserId(),
                parseTime(request.getFrom()),
                parseTime(request.getTo()),
                request.getEventType(),
                request.getPage(),
                request.getSize()
        );
        TradesListResponse.Builder b = TradesListResponse.newBuilder()
                .setTotal(page.getTotal())
                .setPage(page.getPage())
                .setSize(page.getSize());
        page.getItems().forEach(item -> b.addItems(toTradeMessage(item)));
        return b.build();
    }

    @Override
    public world.willfrog.alphafrogmicro.portfolio.idl.ValuationResponse valuation(ValuationRequest request) {
        List<HoldingResponse> holdings = holdingService.listHoldings(request.getPortfolioId(), request.getUserId());
        world.willfrog.alphafrogmicro.portfolioservice.dto.ValuationResponse dto = ValuationMapper.mockValuation(holdings);
        world.willfrog.alphafrogmicro.portfolio.idl.ValuationResponse.Builder b =
                world.willfrog.alphafrogmicro.portfolio.idl.ValuationResponse.newBuilder()
                .setTotalValue(toStr(dto.getTotalValue()))
                .setPnlAbs(toStr(dto.getPnlAbs()))
                .setPnlPct(toStr(dto.getPnlPct()));
        dto.getPositions().forEach(p -> b.addPositions(
                ValuationPositionMessage.newBuilder()
                        .setSymbol(nvl(p.getSymbol()))
                        .setSymbolType(nvl(p.getSymbolType()))
                        .setQuantity(toStr(p.getQuantity()))
                        .setLastPrice(toStr(p.getLastPrice()))
                        .setMarketValue(toStr(p.getMarketValue()))
                        .build()
        ));
        return b.build();
    }

    @Override
    public world.willfrog.alphafrogmicro.portfolio.idl.MetricsResponse metrics(MetricsRequest request) {
        world.willfrog.alphafrogmicro.portfolio.idl.MetricsResponse.Builder b =
                world.willfrog.alphafrogmicro.portfolio.idl.MetricsResponse.newBuilder();
        b.setReturnPct("0").setVolatility("0").setMaxDrawdown("0").setNote("占位实现，后续接入行情计算");
        return b.build();
    }

    private PortfolioMessage toProto(PortfolioResponse resp) {
        PortfolioMessage.Builder b = PortfolioMessage.newBuilder()
                .setId(resp.getId())
                .setUserId(nvl(resp.getUserId()))
                .setName(nvl(resp.getName()))
                .setVisibility(nvl(resp.getVisibility()))
                .setStatus(nvl(resp.getStatus()))
                .setTimezone(nvl(resp.getTimezone()))
                .setCreatedAt(resp.getCreatedAt() != null ? resp.getCreatedAt().toString() : "")
                .setUpdatedAt(resp.getUpdatedAt() != null ? resp.getUpdatedAt().toString() : "");
        if (resp.getTags() != null) {
            b.addAllTags(resp.getTags());
        }
        return b.build();
    }

    private HoldingsListResponse toHoldingsProto(List<HoldingResponse> list) {
        HoldingsListResponse.Builder b = HoldingsListResponse.newBuilder();
        list.forEach(h -> b.addItems(
                HoldingMessage.newBuilder()
                        .setId(nvl(h.getId()))
                        .setPortfolioId(nvl(h.getPortfolioId()))
                        .setSymbol(nvl(h.getSymbol()))
                        .setSymbolType(nvl(h.getSymbolType()))
                        .setExchange(nvl(h.getExchange()))
                        .setPositionSide(nvl(h.getPositionSide()))
                        .setQuantity(toStr(h.getQuantity()))
                        .setAvgCost(toStr(h.getAvgCost()))
                        .setUpdatedAt(h.getUpdatedAt() != null ? h.getUpdatedAt().toString() : "")
                        .build()
        ));
        return b.build();
    }

    private HoldingUpsertItem toHoldingItem(HoldingMessage msg) {
        HoldingUpsertItem item = new HoldingUpsertItem();
        item.setSymbol(msg.getSymbol());
        item.setSymbolType(msg.getSymbolType());
        item.setExchange(msg.getExchange());
        item.setPositionSide(msg.getPositionSide());
        item.setQuantity(toDecimal(msg.getQuantity()));
        item.setAvgCost(toDecimal(msg.getAvgCost()));
        return item;
    }

    private TradeCreateItem toTradeItem(TradeMessage msg) {
        TradeCreateItem item = new TradeCreateItem();
        item.setSymbol(msg.getSymbol());
        item.setEventType(msg.getEventType());
        item.setQuantity(toDecimal(msg.getQuantity()));
        item.setPrice(toDecimal(msg.getPrice()));
        item.setFee(toDecimal(msg.getFee()));
        item.setSlippage(toDecimal(msg.getSlippage()));
        item.setTradeTime(parseTime(msg.getTradeTime()));
        item.setSettleDate(parseTime(msg.getSettleDate()));
        item.setNote(msg.getNote());
        item.setPayloadJson("");
        return item;
    }

    private TradeMessage toTradeMessage(TradeResponse resp) {
        return TradeMessage.newBuilder()
                .setId(nvl(resp.getId()))
                .setPortfolioId(nvl(resp.getPortfolioId()))
                .setSymbol(nvl(resp.getSymbol()))
                .setEventType(nvl(resp.getEventType()))
                .setQuantity(toStr(resp.getQuantity()))
                .setPrice(toStr(resp.getPrice()))
                .setFee(toStr(resp.getFee()))
                .setSlippage(toStr(resp.getSlippage()))
                .setTradeTime(resp.getTradeTime() != null ? resp.getTradeTime().toString() : "")
                .setSettleDate(resp.getSettleDate() != null ? resp.getSettleDate().toString() : "")
                .setNote(nvl(resp.getNote()))
                .build();
    }

    private String toStr(BigDecimal d) {
        return d == null ? "" : d.toPlainString();
    }

    private BigDecimal toDecimal(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return new BigDecimal(s);
    }

    private OffsetDateTime parseTime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(s);
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private long nvl(Long v) {
        return v == null ? 0L : v;
    }
}

package world.willfrog.alphafrogmicro.portfolioservice;

import org.apache.dubbo.config.annotation.DubboService;
import world.willfrog.alphafrogmicro.portfolio.idl.*;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PageResult;
import world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyBacktestRunResponse;
import world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyNavResponse;
import world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyResponse;
import world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyTargetResponse;
import world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyTargetUpsertItem;
import world.willfrog.alphafrogmicro.portfolioservice.service.StrategyService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Dubbo gRPC 入口，面向 frontend。
 */
@DubboService
public class StrategyDubboServiceImpl extends DubboStrategyDubboServiceTriple.StrategyDubboServiceImplBase {

    private final StrategyService strategyService;

    public StrategyDubboServiceImpl(StrategyService strategyService) {
        this.strategyService = strategyService;
    }

    @Override
    public StrategyMessage createStrategy(StrategyCreateRequest request) {
        world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyCreateRequest dto =
                new world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyCreateRequest();
        dto.setName(request.getName());
        dto.setDescription(request.getDescription());
        dto.setRuleJson(request.getRuleJson());
        dto.setRebalanceRule(request.getRebalanceRule());
        dto.setCapitalBase(toDecimal(request.getCapitalBase()));
        dto.setStartDate(parseDate(request.getStartDate()));
        dto.setEndDate(parseDate(request.getEndDate()));
        dto.setBaseCurrency(request.getBaseCurrency());
        dto.setBenchmarkSymbol(request.getBenchmarkSymbol());
        return toStrategyMessage(strategyService.create(request.getUserId(), dto));
    }

    @Override
    public StrategyMessage updateStrategy(StrategyUpdateRequest request) {
        world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyUpdateRequest dto =
                new world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyUpdateRequest();
        dto.setName(request.getName());
        dto.setDescription(request.getDescription());
        dto.setRuleJson(request.getRuleJson());
        dto.setRebalanceRule(request.getRebalanceRule());
        dto.setCapitalBase(toDecimal(request.getCapitalBase()));
        dto.setStartDate(parseDate(request.getStartDate()));
        dto.setEndDate(parseDate(request.getEndDate()));
        dto.setStatus(request.getStatus());
        dto.setBaseCurrency(request.getBaseCurrency());
        dto.setBenchmarkSymbol(request.getBenchmarkSymbol());
        return toStrategyMessage(strategyService.update(request.getId(), request.getUserId(), dto));
    }

    @Override
    public PortfolioEmpty archiveStrategy(StrategyArchiveRequest request) {
        strategyService.archive(request.getId(), request.getUserId());
        return PortfolioEmpty.newBuilder().build();
    }

    @Override
    public StrategyMessage getStrategy(StrategyGetRequest request) {
        return toStrategyMessage(strategyService.getById(request.getId(), request.getUserId()));
    }

    @Override
    public StrategyListResponse listStrategy(StrategyListRequest request) {
        PageResult<StrategyResponse> page = strategyService.list(
                request.getUserId(),
                request.getStatus(),
                request.getKeyword(),
                request.getPage(),
                request.getSize()
        );
        StrategyListResponse.Builder b = StrategyListResponse.newBuilder()
                .setTotal(page.getTotal())
                .setPage(page.getPage())
                .setSize(page.getSize());
        page.getItems().forEach(item -> b.addItems(toStrategyMessage(item)));
        return b.build();
    }

    @Override
    public StrategyTargetListResponse targetsBulkUpsert(StrategyTargetUpsertRequest request) {
        world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyTargetUpsertRequest dto =
                new world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyTargetUpsertRequest();
        dto.setItems(request.getItemsList().stream().map(this::toTargetItem).toList());
        List<StrategyTargetResponse> list = strategyService.upsertTargets(request.getStrategyId(), request.getUserId(), dto);
        return toTargetListResponse(list);
    }

    @Override
    public StrategyTargetListResponse targetsList(StrategyTargetListRequest request) {
        return toTargetListResponse(strategyService.listTargets(request.getStrategyId(), request.getUserId()));
    }

    @Override
    public StrategyBacktestRunMessage backtestRunCreate(StrategyBacktestRunCreateRequest request) {
        world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyBacktestRunCreateRequest dto =
                new world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyBacktestRunCreateRequest();
        dto.setStartDate(parseDate(request.getStartDate()));
        dto.setEndDate(parseDate(request.getEndDate()));
        dto.setParamsJson(request.getParamsJson());
        return toRunMessage(strategyService.createBacktestRun(request.getStrategyId(), request.getUserId(), dto));
    }

    @Override
    public StrategyBacktestRunListResponse backtestRunList(StrategyBacktestRunListRequest request) {
        PageResult<StrategyBacktestRunResponse> page = strategyService.listBacktestRuns(
                request.getStrategyId(),
                request.getUserId(),
                request.getStatus(),
                request.getPage(),
                request.getSize()
        );
        StrategyBacktestRunListResponse.Builder b = StrategyBacktestRunListResponse.newBuilder()
                .setTotal(page.getTotal())
                .setPage(page.getPage())
                .setSize(page.getSize());
        page.getItems().forEach(item -> b.addItems(toRunMessage(item)));
        return b.build();
    }

    @Override
    public StrategyNavListResponse navList(StrategyNavListRequest request) {
        PageResult<StrategyNavResponse> page = strategyService.listNav(
                request.getStrategyId(),
                request.getRunId(),
                request.getUserId(),
                parseDate(request.getFrom()),
                parseDate(request.getTo()),
                request.getPage(),
                request.getSize()
        );
        StrategyNavListResponse.Builder b = StrategyNavListResponse.newBuilder()
                .setTotal(page.getTotal())
                .setPage(page.getPage())
                .setSize(page.getSize());
        page.getItems().forEach(item -> b.addItems(toNavMessage(item)));
        return b.build();
    }

    private StrategyMessage toStrategyMessage(StrategyResponse resp) {
        return StrategyMessage.newBuilder()
                .setId(nvl(resp.getId()))
                .setPortfolioId(nvl(resp.getPortfolioId()))
                .setUserId(nvl(resp.getUserId()))
                .setName(nvl(resp.getName()))
                .setDescription(nvl(resp.getDescription()))
                .setRuleJson(nvl(resp.getRuleJson()))
                .setRebalanceRule(nvl(resp.getRebalanceRule()))
                .setCapitalBase(toStr(resp.getCapitalBase()))
                .setStartDate(toStr(resp.getStartDate()))
                .setEndDate(toStr(resp.getEndDate()))
                .setStatus(nvl(resp.getStatus()))
                .setBaseCurrency(nvl(resp.getBaseCurrency()))
                .setBenchmarkSymbol(nvl(resp.getBenchmarkSymbol()))
                .setCreatedAt(toStr(resp.getCreatedAt()))
                .setUpdatedAt(toStr(resp.getUpdatedAt()))
                .build();
    }

    private StrategyTargetListResponse toTargetListResponse(List<StrategyTargetResponse> list) {
        StrategyTargetListResponse.Builder b = StrategyTargetListResponse.newBuilder();
        list.forEach(item -> b.addItems(toTargetMessage(item)));
        return b.build();
    }

    private StrategyTargetMessage toTargetMessage(StrategyTargetResponse resp) {
        return StrategyTargetMessage.newBuilder()
                .setId(nvl(resp.getId()))
                .setStrategyId(nvl(resp.getStrategyId()))
                .setSymbol(nvl(resp.getSymbol()))
                .setSymbolType(nvl(resp.getSymbolType()))
                .setTargetWeight(toStr(resp.getTargetWeight()))
                .setEffectiveDate(toStr(resp.getEffectiveDate()))
                .setUpdatedAt(toStr(resp.getUpdatedAt()))
                .build();
    }

    private StrategyTargetUpsertItem toTargetItem(StrategyTargetMessage msg) {
        StrategyTargetUpsertItem item = new StrategyTargetUpsertItem();
        item.setSymbol(msg.getSymbol());
        item.setSymbolType(msg.getSymbolType());
        item.setTargetWeight(toDecimal(msg.getTargetWeight()));
        item.setEffectiveDate(parseDate(msg.getEffectiveDate()));
        return item;
    }

    private StrategyBacktestRunMessage toRunMessage(StrategyBacktestRunResponse resp) {
        return StrategyBacktestRunMessage.newBuilder()
                .setId(nvl(resp.getId()))
                .setStrategyId(nvl(resp.getStrategyId()))
                .setRunTime(toStr(resp.getRunTime()))
                .setStartDate(toStr(resp.getStartDate()))
                .setEndDate(toStr(resp.getEndDate()))
                .setStatus(nvl(resp.getStatus()))
                .build();
    }

    private StrategyNavMessage toNavMessage(StrategyNavResponse resp) {
        return StrategyNavMessage.newBuilder()
                .setId(nvl(resp.getId()))
                .setRunId(nvl(resp.getRunId()))
                .setTradeDate(toStr(resp.getTradeDate()))
                .setNav(toStr(resp.getNav()))
                .setReturnPct(toStr(resp.getReturnPct()))
                .setBenchmarkNav(toStr(resp.getBenchmarkNav()))
                .setDrawdown(toStr(resp.getDrawdown()))
                .build();
    }

    private String toStr(BigDecimal d) {
        return d == null ? "" : d.toPlainString();
    }

    private String toStr(LocalDate d) {
        return d == null ? "" : d.toString();
    }

    private String toStr(OffsetDateTime d) {
        return d == null ? "" : d.toString();
    }

    private BigDecimal toDecimal(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return new BigDecimal(s);
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return LocalDate.parse(s);
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private long nvl(Long v) {
        return v == null ? 0L : v;
    }
}

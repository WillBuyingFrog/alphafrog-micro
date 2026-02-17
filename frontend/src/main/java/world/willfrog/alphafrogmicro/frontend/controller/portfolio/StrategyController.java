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
import world.willfrog.alphafrogmicro.frontend.service.AuthService;
import world.willfrog.alphafrogmicro.portfolio.idl.StrategyBacktestRunListRequest;
import world.willfrog.alphafrogmicro.portfolio.idl.StrategyBacktestRunListResponse;
import world.willfrog.alphafrogmicro.portfolio.idl.StrategyBacktestRunMessage;
import world.willfrog.alphafrogmicro.portfolio.idl.StrategyDubboService;
import world.willfrog.alphafrogmicro.portfolio.idl.StrategyGetRequest;
import world.willfrog.alphafrogmicro.portfolio.idl.StrategyListRequest;
import world.willfrog.alphafrogmicro.portfolio.idl.StrategyListResponse;
import world.willfrog.alphafrogmicro.portfolio.idl.StrategyMessage;
import world.willfrog.alphafrogmicro.portfolio.idl.StrategyNavListRequest;
import world.willfrog.alphafrogmicro.portfolio.idl.StrategyNavListResponse;
import world.willfrog.alphafrogmicro.portfolio.idl.StrategyNavMessage;
import world.willfrog.alphafrogmicro.portfolio.idl.StrategyTargetListRequest;
import world.willfrog.alphafrogmicro.portfolio.idl.StrategyTargetListResponse;
import world.willfrog.alphafrogmicro.portfolio.idl.StrategyTargetMessage;
import world.willfrog.alphafrogmicro.portfolio.idl.StrategyArchiveRequest;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PageResult;
import world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyBacktestRunCreateRequest;
import world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyBacktestRunResponse;
import world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyCreateRequest;
import world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyNavResponse;
import world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyResponse;
import world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyTargetResponse;
import world.willfrog.alphafrogmicro.portfolioservice.dto.StrategyUpdateRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/strategies")
@RequiredArgsConstructor
@Slf4j
public class StrategyController {

    @DubboReference
    private StrategyDubboService strategyDubboService;

    private final AuthService authService;

    @PostMapping
    public ResponseWrapper<StrategyResponse> create(Authentication authentication,
                                                    @RequestBody StrategyCreateRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            world.willfrog.alphafrogmicro.portfolio.idl.StrategyCreateRequest req =
                    world.willfrog.alphafrogmicro.portfolio.idl.StrategyCreateRequest.newBuilder()
                            .setUserId(userId)
                            .setName(nvl(request.getName()))
                            .setDescription(nvl(request.getDescription()))
                            .setRuleJson(nvl(request.getRuleJson()))
                            .setRebalanceRule(nvl(request.getRebalanceRule()))
                            .setCapitalBase(toStr(request.getCapitalBase()))
                            .setStartDate(toStr(request.getStartDate()))
                            .setEndDate(toStr(request.getEndDate()))
                            .setBaseCurrency(nvl(request.getBaseCurrency()))
                            .setBenchmarkSymbol(nvl(request.getBenchmarkSymbol()))
                            .build();
            StrategyMessage response = strategyDubboService.createStrategy(req);
            return ResponseWrapper.success(toStrategyResponse(response));
        } catch (RpcException e) {
            return handleRpcError(e, "创建策略");
        } catch (Exception e) {
            return handleError(e, "创建策略");
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
            StrategyListRequest req = StrategyListRequest.newBuilder()
                    .setUserId(userId)
                    .setStatus(nvl(status))
                    .setKeyword(nvl(keyword))
                    .setPage(page)
                    .setSize(size)
                    .build();
            StrategyListResponse response = strategyDubboService.listStrategy(req);
            if (isCompact(format)) {
                CompactMeta meta = CompactJsonConverter.extractPageMetaFromResponse(response, response.getPage(), response.getSize());
                CompactApiResponse compact = CompactJsonConverter.convert(response, null, meta);
                return ResponseWrapper.success(compact);
            }
            return ResponseWrapper.success(toStrategyPage(response));
        } catch (RpcException e) {
            return handleRpcError(e, "查询策略列表");
        } catch (Exception e) {
            return handleError(e, "查询策略列表");
        }
    }

    @GetMapping("/{id}")
    public ResponseWrapper<StrategyResponse> getById(Authentication authentication,
                                                     @PathVariable("id") Long id) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            StrategyGetRequest req = StrategyGetRequest.newBuilder()
                    .setUserId(userId)
                    .setId(id)
                    .build();
            StrategyMessage response = strategyDubboService.getStrategy(req);
            return ResponseWrapper.success(toStrategyResponse(response));
        } catch (RpcException e) {
            return handleRpcError(e, "查询策略");
        } catch (Exception e) {
            return handleError(e, "查询策略");
        }
    }

    @PatchMapping("/{id}")
    public ResponseWrapper<StrategyResponse> update(Authentication authentication,
                                                    @PathVariable("id") Long id,
                                                    @RequestBody StrategyUpdateRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            world.willfrog.alphafrogmicro.portfolio.idl.StrategyUpdateRequest req =
                    world.willfrog.alphafrogmicro.portfolio.idl.StrategyUpdateRequest.newBuilder()
                    .setUserId(userId)
                    .setId(id)
                    .setName(nvl(request.getName()))
                    .setDescription(nvl(request.getDescription()))
                    .setRuleJson(nvl(request.getRuleJson()))
                    .setRebalanceRule(nvl(request.getRebalanceRule()))
                    .setCapitalBase(toStr(request.getCapitalBase()))
                    .setStartDate(toStr(request.getStartDate()))
                    .setEndDate(toStr(request.getEndDate()))
                    .setStatus(nvl(request.getStatus()))
                    .setBaseCurrency(nvl(request.getBaseCurrency()))
                    .setBenchmarkSymbol(nvl(request.getBenchmarkSymbol()))
                    .build();
            StrategyMessage response = strategyDubboService.updateStrategy(req);
            return ResponseWrapper.success(toStrategyResponse(response));
        } catch (RpcException e) {
            return handleRpcError(e, "更新策略");
        } catch (Exception e) {
            return handleError(e, "更新策略");
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
            StrategyArchiveRequest req = StrategyArchiveRequest.newBuilder()
                    .setUserId(userId)
                    .setId(id)
                    .build();
            strategyDubboService.archiveStrategy(req);
            return ResponseWrapper.success(null);
        } catch (RpcException e) {
            return handleRpcError(e, "归档策略");
        } catch (Exception e) {
            return handleError(e, "归档策略");
        }
    }

    @PostMapping("/{id}/targets:bulk-upsert")
    public ResponseWrapper<List<StrategyTargetResponse>> upsertTargets(Authentication authentication,
                                                                       @PathVariable("id") Long strategyId,
                                                                       @RequestBody TargetBulkUpsertRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            return ResponseWrapper.error(ResponseCode.PARAM_ERROR, "items 不能为空");
        }
        try {
            world.willfrog.alphafrogmicro.portfolio.idl.StrategyTargetUpsertRequest.Builder builder =
                    world.willfrog.alphafrogmicro.portfolio.idl.StrategyTargetUpsertRequest.newBuilder()
                    .setUserId(userId)
                    .setStrategyId(strategyId);
            if (request.getItems() != null) {
                for (TargetBulkUpsertItem item : request.getItems()) {
                    builder.addItems(StrategyTargetMessage.newBuilder()
                            .setSymbol(nvl(item.getSymbol()))
                            .setSymbolType(nvl(item.getSymbolType()))
                            .setTargetWeight(toStr(item.getTargetWeight()))
                            .setEffectiveDate(toStr(item.getEffectiveDate()))
                            .build());
                }
            }
            StrategyTargetListResponse response = strategyDubboService.targetsBulkUpsert(builder.build());
            return ResponseWrapper.success(toTargetList(response));
        } catch (RpcException e) {
            return handleRpcError(e, "更新目标权重");
        } catch (Exception e) {
            return handleError(e, "更新目标权重");
        }
    }

    @GetMapping("/{id}/targets")
    public ResponseWrapper<?> listTargets(Authentication authentication,
                                          @PathVariable("id") Long strategyId,
                                          @RequestParam(value = "format", required = false, defaultValue = "compact") String format) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            StrategyTargetListRequest req = StrategyTargetListRequest.newBuilder()
                    .setUserId(userId)
                    .setStrategyId(strategyId)
                    .build();
            StrategyTargetListResponse response = strategyDubboService.targetsList(req);
            if (isCompact(format)) {
                CompactApiResponse compact = CompactJsonConverter.convert(response, null, null);
                return ResponseWrapper.success(compact);
            }
            return ResponseWrapper.success(toTargetList(response));
        } catch (RpcException e) {
            return handleRpcError(e, "查询目标权重");
        } catch (Exception e) {
            return handleError(e, "查询目标权重");
        }
    }

    @PostMapping("/{id}/backtests")
    public ResponseWrapper<StrategyBacktestRunResponse> createBacktest(Authentication authentication,
                                                                       @PathVariable("id") Long strategyId,
                                                                       @RequestBody StrategyBacktestRunCreateRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            world.willfrog.alphafrogmicro.portfolio.idl.StrategyBacktestRunCreateRequest req =
                    world.willfrog.alphafrogmicro.portfolio.idl.StrategyBacktestRunCreateRequest.newBuilder()
                    .setUserId(userId)
                    .setStrategyId(strategyId)
                    .setStartDate(toStr(request.getStartDate()))
                    .setEndDate(toStr(request.getEndDate()))
                    .setParamsJson(nvl(request.getParamsJson()))
                    .build();
            StrategyBacktestRunMessage response = strategyDubboService.backtestRunCreate(req);
            return ResponseWrapper.success(toBacktestResponse(response));
        } catch (RpcException e) {
            return handleRpcError(e, "创建回测");
        } catch (Exception e) {
            return handleError(e, "创建回测");
        }
    }

    @GetMapping("/{id}/backtests")
    public ResponseWrapper<?> listBacktests(Authentication authentication,
                                            @PathVariable("id") Long strategyId,
                                            @RequestParam(value = "status", required = false) String status,
                                            @RequestParam(value = "page", defaultValue = "1") int page,
                                            @RequestParam(value = "size", defaultValue = "20") int size,
                                            @RequestParam(value = "format", required = false, defaultValue = "compact") String format) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            StrategyBacktestRunListRequest req = StrategyBacktestRunListRequest.newBuilder()
                    .setUserId(userId)
                    .setStrategyId(strategyId)
                    .setStatus(nvl(status))
                    .setPage(page)
                    .setSize(size)
                    .build();
            StrategyBacktestRunListResponse response = strategyDubboService.backtestRunList(req);
            if (isCompact(format)) {
                CompactMeta meta = CompactJsonConverter.extractPageMetaFromResponse(response, response.getPage(), response.getSize());
                CompactApiResponse compact = CompactJsonConverter.convert(response, null, meta);
                return ResponseWrapper.success(compact);
            }
            return ResponseWrapper.success(toBacktestPage(response));
        } catch (RpcException e) {
            return handleRpcError(e, "查询回测");
        } catch (Exception e) {
            return handleError(e, "查询回测");
        }
    }

    @GetMapping("/{id}/backtests/{runId}/nav")
    public ResponseWrapper<?> listNav(Authentication authentication,
                                      @PathVariable("id") Long strategyId,
                                      @PathVariable("runId") Long runId,
                                      @RequestParam(value = "from", required = false) String from,
                                      @RequestParam(value = "to", required = false) String to,
                                      @RequestParam(value = "page", defaultValue = "1") int page,
                                      @RequestParam(value = "size", defaultValue = "200") int size,
                                      @RequestParam(value = "format", required = false, defaultValue = "compact") String format) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            StrategyNavListRequest req = StrategyNavListRequest.newBuilder()
                    .setUserId(userId)
                    .setStrategyId(strategyId)
                    .setRunId(runId)
                    .setFrom(nvl(from))
                    .setTo(nvl(to))
                    .setPage(page)
                    .setSize(size)
                    .build();
            StrategyNavListResponse response = strategyDubboService.navList(req);
            if (isCompact(format)) {
                CompactMeta meta = CompactJsonConverter.extractPageMetaFromResponse(response, response.getPage(), response.getSize());
                CompactApiResponse compact = CompactJsonConverter.convert(response, null, meta);
                return ResponseWrapper.success(compact);
            }
            return ResponseWrapper.success(toNavPage(response));
        } catch (RpcException e) {
            return handleRpcError(e, "查询净值");
        } catch (Exception e) {
            return handleError(e, "查询净值");
        }
    }

    private PageResult<StrategyResponse> toStrategyPage(StrategyListResponse response) {
        List<StrategyResponse> items = new ArrayList<>();
        for (StrategyMessage msg : response.getItemsList()) {
            items.add(toStrategyResponse(msg));
        }
        return PageResult.<StrategyResponse>builder()
                .items(items)
                .total(response.getTotal())
                .page(response.getPage())
                .size(response.getSize())
                .build();
    }

    private StrategyResponse toStrategyResponse(StrategyMessage msg) {
        return StrategyResponse.builder()
                .id(msg.getId())
                .portfolioId(msg.getPortfolioId())
                .userId(emptyToNull(msg.getUserId()))
                .name(emptyToNull(msg.getName()))
                .description(emptyToNull(msg.getDescription()))
                .ruleJson(emptyToNull(msg.getRuleJson()))
                .rebalanceRule(emptyToNull(msg.getRebalanceRule()))
                .capitalBase(toDecimal(msg.getCapitalBase()))
                .startDate(parseDate(msg.getStartDate()))
                .endDate(parseDate(msg.getEndDate()))
                .status(emptyToNull(msg.getStatus()))
                .baseCurrency(emptyToNull(msg.getBaseCurrency()))
                .benchmarkSymbol(emptyToNull(msg.getBenchmarkSymbol()))
                .createdAt(parseTime(msg.getCreatedAt()))
                .updatedAt(parseTime(msg.getUpdatedAt()))
                .build();
    }

    private List<StrategyTargetResponse> toTargetList(StrategyTargetListResponse response) {
        List<StrategyTargetResponse> list = new ArrayList<>();
        for (StrategyTargetMessage msg : response.getItemsList()) {
            list.add(StrategyTargetResponse.builder()
                    .id(msg.getId())
                    .strategyId(msg.getStrategyId())
                    .symbol(emptyToNull(msg.getSymbol()))
                    .symbolType(emptyToNull(msg.getSymbolType()))
                    .targetWeight(toDecimal(msg.getTargetWeight()))
                    .effectiveDate(parseDate(msg.getEffectiveDate()))
                    .updatedAt(parseTime(msg.getUpdatedAt()))
                    .build());
        }
        return list;
    }

    private StrategyBacktestRunResponse toBacktestResponse(StrategyBacktestRunMessage msg) {
        return StrategyBacktestRunResponse.builder()
                .id(msg.getId())
                .strategyId(msg.getStrategyId())
                .runTime(parseTime(msg.getRunTime()))
                .startDate(parseDate(msg.getStartDate()))
                .endDate(parseDate(msg.getEndDate()))
                .status(emptyToNull(msg.getStatus()))
                .build();
    }

    private PageResult<StrategyBacktestRunResponse> toBacktestPage(StrategyBacktestRunListResponse response) {
        List<StrategyBacktestRunResponse> list = new ArrayList<>();
        for (StrategyBacktestRunMessage msg : response.getItemsList()) {
            list.add(toBacktestResponse(msg));
        }
        return PageResult.<StrategyBacktestRunResponse>builder()
                .items(list)
                .total(response.getTotal())
                .page(response.getPage())
                .size(response.getSize())
                .build();
    }

    private PageResult<StrategyNavResponse> toNavPage(StrategyNavListResponse response) {
        List<StrategyNavResponse> list = new ArrayList<>();
        for (StrategyNavMessage msg : response.getItemsList()) {
            list.add(StrategyNavResponse.builder()
                    .id(msg.getId())
                    .runId(msg.getRunId())
                    .tradeDate(parseDate(msg.getTradeDate()))
                    .nav(toDecimal(msg.getNav()))
                    .returnPct(toDecimal(msg.getReturnPct()))
                    .benchmarkNav(toDecimal(msg.getBenchmarkNav()))
                    .drawdown(toDecimal(msg.getDrawdown()))
                    .build());
        }
        return PageResult.<StrategyNavResponse>builder()
                .items(list)
                .total(response.getTotal())
                .page(response.getPage())
                .size(response.getSize())
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

    private String toStr(LocalDate value) {
        return value == null ? "" : value.toString();
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value);
    }

    private OffsetDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(value);
    }

    public static class TargetBulkUpsertRequest {
        private List<TargetBulkUpsertItem> items;

        public List<TargetBulkUpsertItem> getItems() {
            return items;
        }

        public void setItems(List<TargetBulkUpsertItem> items) {
            this.items = items;
        }
    }

    public static class TargetBulkUpsertItem {
        private String symbol;
        private String symbolType;
        private BigDecimal targetWeight;
        private LocalDate effectiveDate;

        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }

        public String getSymbolType() {
            return symbolType;
        }

        public void setSymbolType(String symbolType) {
            this.symbolType = symbolType;
        }

        public BigDecimal getTargetWeight() {
            return targetWeight;
        }

        public void setTargetWeight(BigDecimal targetWeight) {
            this.targetWeight = targetWeight;
        }

        public LocalDate getEffectiveDate() {
            return effectiveDate;
        }

        public void setEffectiveDate(LocalDate effectiveDate) {
            this.effectiveDate = effectiveDate;
        }
    }
}

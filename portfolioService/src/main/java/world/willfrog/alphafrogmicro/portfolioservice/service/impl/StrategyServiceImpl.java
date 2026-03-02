package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.portfolioservice.constants.PortfolioConstants;
import world.willfrog.alphafrogmicro.portfolioservice.domain.*;
import world.willfrog.alphafrogmicro.portfolioservice.dto.*;
import world.willfrog.alphafrogmicro.portfolioservice.exception.BizException;
import world.willfrog.alphafrogmicro.portfolioservice.mapper.*;
import world.willfrog.alphafrogmicro.portfolioservice.service.StrategyService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class StrategyServiceImpl implements StrategyService {

    private static final Set<String> STRATEGY_STATUS_SET = Set.of("active", "archived");
    private static final Set<String> SYMBOL_TYPES = Set.of("stock", "etf", "index", "fund");

    private final StrategyDefinitionMapper strategyDefinitionMapper;
    private final StrategyTargetMapper strategyTargetMapper;
    private final StrategyBacktestRunMapper strategyBacktestRunMapper;
    private final StrategyNavMapper strategyNavMapper;
    private final PortfolioMapper portfolioMapper;
    private final world.willfrog.alphafrogmicro.portfolioservice.backtest.StrategyBacktestPublisher backtestPublisher;

    public StrategyServiceImpl(StrategyDefinitionMapper strategyDefinitionMapper,
                               StrategyTargetMapper strategyTargetMapper,
                               StrategyBacktestRunMapper strategyBacktestRunMapper,
                               StrategyNavMapper strategyNavMapper,
                               PortfolioMapper portfolioMapper,
                               world.willfrog.alphafrogmicro.portfolioservice.backtest.StrategyBacktestPublisher backtestPublisher) {
        this.strategyDefinitionMapper = strategyDefinitionMapper;
        this.strategyTargetMapper = strategyTargetMapper;
        this.strategyBacktestRunMapper = strategyBacktestRunMapper;
        this.strategyNavMapper = strategyNavMapper;
        this.portfolioMapper = portfolioMapper;
        this.backtestPublisher = backtestPublisher;
    }

    @Override
    @Transactional
    public StrategyResponse create(String userId, StrategyCreateRequest request) {
        if (portfolioMapper.countActiveName(userId, request.getName()) > 0) {
            throw new BizException(ResponseCode.DATA_EXIST, "同名组合已存在");
        }

        PortfolioPo portfolio = new PortfolioPo();
        portfolio.setUserId(userId);
        portfolio.setName(request.getName());
        portfolio.setVisibility(PortfolioConstants.DEFAULT_VISIBILITY);
        portfolio.setTagsJson("[]");
        portfolio.setPortfolioType("STRATEGY");
        portfolio.setBaseCurrency(defaultBaseCurrency(request.getBaseCurrency()));
        portfolio.setBenchmarkSymbol(request.getBenchmarkSymbol());
        portfolio.setStatus(PortfolioConstants.DEFAULT_STATUS);
        portfolio.setTimezone(PortfolioConstants.DEFAULT_TIMEZONE);
        portfolio.setExtJson("{}");
        portfolioMapper.insert(portfolio);

        StrategyDefinitionPo po = new StrategyDefinitionPo();
        po.setPortfolioId(portfolio.getId());
        po.setUserId(userId);
        po.setName(request.getName());
        po.setDescription(request.getDescription());
        po.setRuleJson(defaultJson(request.getRuleJson()));
        po.setRebalanceRule(request.getRebalanceRule());
        po.setCapitalBase(defaultCapitalBase(request.getCapitalBase()));
        po.setStartDate(request.getStartDate());
        po.setEndDate(request.getEndDate());
        po.setStatus(PortfolioConstants.DEFAULT_STATUS);
        po.setExtJson("{}");
        strategyDefinitionMapper.insert(po);

        OffsetDateTime now = OffsetDateTime.now();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        return toResponse(po, portfolio);
    }

    @Override
    public PageResult<StrategyResponse> list(String userId, String status, String keyword, int page, int size) {
        int pageNum = Math.max(page, PortfolioConstants.DEFAULT_PAGE);
        int pageSize = Math.min(Math.max(size, 1), PortfolioConstants.MAX_PAGE_SIZE);
        String normalizedStatus = normalizeStatus(status);
        int offset = (pageNum - 1) * pageSize;

        List<StrategyDefinitionPo> list = strategyDefinitionMapper.list(userId, normalizedStatus, keyword, offset, pageSize);
        long total = strategyDefinitionMapper.count(userId, normalizedStatus, keyword);

        List<StrategyResponse> dtoList = new ArrayList<>();
        for (StrategyDefinitionPo po : list) {
            PortfolioPo portfolio = portfolioMapper.findByIdAndUser(po.getPortfolioId(), userId);
            dtoList.add(toResponse(po, portfolio));
        }

        return PageResult.<StrategyResponse>builder()
                .items(dtoList)
                .total(total)
                .page(pageNum)
                .size(pageSize)
                .build();
    }

    @Override
    public StrategyResponse getById(Long id, String userId) {
        StrategyDefinitionPo po = strategyDefinitionMapper.findByIdAndUser(id, userId);
        if (po == null) {
            throw new BizException(ResponseCode.DATA_NOT_FOUND, "策略不存在");
        }
        PortfolioPo portfolio = requireStrategyPortfolio(po.getPortfolioId(), userId);
        return toResponse(po, portfolio);
    }

    @Override
    @Transactional
    public StrategyResponse update(Long id, String userId, StrategyUpdateRequest request) {
        StrategyDefinitionPo po = strategyDefinitionMapper.findByIdAndUser(id, userId);
        if (po == null) {
            throw new BizException(ResponseCode.DATA_NOT_FOUND, "策略不存在");
        }
        PortfolioPo portfolio = requireStrategyPortfolio(po.getPortfolioId(), userId);

        if (StringUtils.isNotBlank(request.getName())
                && !StringUtils.equals(request.getName(), po.getName())
                && portfolioMapper.countActiveName(userId, request.getName()) > 0) {
            throw new BizException(ResponseCode.DATA_EXIST, "同名组合已存在");
        }

        if (StringUtils.isNotBlank(request.getName())) {
            po.setName(request.getName());
            portfolio.setName(request.getName());
        }
        if (request.getDescription() != null) {
            po.setDescription(request.getDescription());
        }
        if (request.getRuleJson() != null) {
            po.setRuleJson(defaultJson(request.getRuleJson()));
        }
        if (request.getRebalanceRule() != null) {
            po.setRebalanceRule(request.getRebalanceRule());
        }
        if (request.getCapitalBase() != null) {
            po.setCapitalBase(defaultCapitalBase(request.getCapitalBase()));
        }
        if (request.getStartDate() != null) {
            po.setStartDate(request.getStartDate());
        }
        if (request.getEndDate() != null) {
            po.setEndDate(request.getEndDate());
        }
        if (StringUtils.isNotBlank(request.getStatus())) {
            String normalizedStatus = normalizeStatus(request.getStatus());
            po.setStatus(normalizedStatus);
            portfolio.setStatus(normalizedStatus);
        }
        if (StringUtils.isNotBlank(request.getBaseCurrency())) {
            portfolio.setBaseCurrency(defaultBaseCurrency(request.getBaseCurrency()));
        }
        if (StringUtils.isNotBlank(request.getBenchmarkSymbol())) {
            portfolio.setBenchmarkSymbol(request.getBenchmarkSymbol());
        }

        strategyDefinitionMapper.update(po);
        portfolioMapper.update(portfolio);

        return toResponse(po, portfolio);
    }

    @Override
    @Transactional
    public void archive(Long id, String userId) {
        StrategyDefinitionPo po = strategyDefinitionMapper.findByIdAndUser(id, userId);
        if (po == null) {
            throw new BizException(ResponseCode.DATA_NOT_FOUND, "策略不存在");
        }
        strategyDefinitionMapper.archive(id, userId);
        PortfolioPo portfolio = portfolioMapper.findByIdAndUser(po.getPortfolioId(), userId);
        if (portfolio != null) {
            portfolio.setName(buildArchivedName(portfolio.getName(), portfolio.getId()));
            portfolio.setStatus("archived");
            portfolioMapper.update(portfolio);
        } else {
            portfolioMapper.archive(po.getPortfolioId(), userId);
        }
    }

    @Override
    @Transactional
    public List<StrategyTargetResponse> upsertTargets(Long strategyId, String userId, StrategyTargetUpsertRequest request) {
        StrategyDefinitionPo strategy = strategyDefinitionMapper.findByIdAndUser(strategyId, userId);
        if (strategy == null || !"active".equals(strategy.getStatus())) {
            throw new BizException(ResponseCode.DATA_NOT_FOUND, "策略不存在或已归档");
        }

        List<StrategyTargetPo> list = new ArrayList<>();
        for (StrategyTargetUpsertItem item : request.getItems()) {
            validateTarget(item);
            StrategyTargetPo target = new StrategyTargetPo();
            target.setStrategyId(strategyId);
            target.setUserId(userId);
            target.setSymbol(item.getSymbol());
            target.setSymbolType(item.getSymbolType());
            target.setTargetWeight(item.getTargetWeight());
            target.setEffectiveDate(item.getEffectiveDate());
            target.setExtJson("{}");
            list.add(target);
        }

        strategyTargetMapper.deleteByStrategyId(strategyId, userId);
        if (!list.isEmpty()) {
            strategyTargetMapper.insertBatch(list);
        }

        return listTargets(strategyId, userId);
    }

    @Override
    public List<StrategyTargetResponse> listTargets(Long strategyId, String userId) {
        List<StrategyTargetPo> list = strategyTargetMapper.listByStrategy(strategyId, userId);
        return list.stream().map(this::toTargetResponse).toList();
    }

    @Override
    @Transactional
    public StrategyBacktestRunResponse createBacktestRun(Long strategyId, String userId, StrategyBacktestRunCreateRequest request) {
        StrategyDefinitionPo strategy = strategyDefinitionMapper.findByIdAndUser(strategyId, userId);
        if (strategy == null || !"active".equals(strategy.getStatus())) {
            throw new BizException(ResponseCode.DATA_NOT_FOUND, "策略不存在或已归档");
        }

        OffsetDateTime now = OffsetDateTime.now();
        // 写入回测任务（pending），随后由 RabbitMQ 消费端执行回测
        StrategyBacktestRunPo run = new StrategyBacktestRunPo();
        run.setStrategyId(strategyId);
        run.setUserId(userId);
        run.setRunTime(now);
        run.setStartDate(request.getStartDate());
        run.setEndDate(request.getEndDate());
        run.setParamsJson(defaultJson(request.getParamsJson()));
        run.setStatus("pending");
        run.setQueuedAt(now);
        run.setExtJson("{}");
        strategyBacktestRunMapper.insert(run);
        try {
            // 发布回测事件，失败则将任务标记为 failed
            backtestPublisher.publish(
                    new world.willfrog.alphafrogmicro.portfolioservice.backtest.StrategyBacktestRunEvent(
                            run.getId(),
                            strategyId,
                            userId
                    )
            );
        } catch (Exception e) {
            String message = StringUtils.abbreviate(e.getMessage(), 500);
            strategyBacktestRunMapper.markFinished(run.getId(), userId, "failed", OffsetDateTime.now(), message);
        }

        return toRunResponse(run);
    }

    @Override
    public PageResult<StrategyBacktestRunResponse> listBacktestRuns(Long strategyId,
                                                                    String userId,
                                                                    String status,
                                                                    int page,
                                                                    int size) {
        StrategyDefinitionPo strategy = strategyDefinitionMapper.findByIdAndUser(strategyId, userId);
        if (strategy == null) {
            throw new BizException(ResponseCode.DATA_NOT_FOUND, "策略不存在");
        }

        int pageNum = Math.max(page, PortfolioConstants.DEFAULT_PAGE);
        int pageSize = Math.min(Math.max(size, 1), PortfolioConstants.MAX_PAGE_SIZE);
        int offset = (pageNum - 1) * pageSize;

        List<StrategyBacktestRunPo> list = strategyBacktestRunMapper.listByStrategy(strategyId, userId, status, offset, pageSize);
        long total = strategyBacktestRunMapper.countByStrategy(strategyId, userId, status);

        List<StrategyBacktestRunResponse> dtoList = list.stream().map(this::toRunResponse).toList();
        return PageResult.<StrategyBacktestRunResponse>builder()
                .items(dtoList)
                .total(total)
                .page(pageNum)
                .size(pageSize)
                .build();
    }

    @Override
    public PageResult<StrategyNavResponse> listNav(Long strategyId,
                                                   Long runId,
                                                   String userId,
                                                   LocalDate from,
                                                   LocalDate to,
                                                   int page,
                                                   int size) {
        StrategyBacktestRunPo run = strategyBacktestRunMapper.findByIdAndUser(runId, userId);
        if (run == null || !run.getStrategyId().equals(strategyId)) {
            throw new BizException(ResponseCode.DATA_NOT_FOUND, "回测记录不存在");
        }

        int pageNum = Math.max(page, PortfolioConstants.DEFAULT_PAGE);
        int pageSize = Math.min(Math.max(size, 1), PortfolioConstants.MAX_PAGE_SIZE);
        int offset = (pageNum - 1) * pageSize;

        List<StrategyNavPo> list = strategyNavMapper.listByRun(runId, userId, from, to, offset, pageSize);
        long total = strategyNavMapper.countByRun(runId, userId, from, to);

        List<StrategyNavResponse> dtoList = list.stream().map(this::toNavResponse).toList();
        return PageResult.<StrategyNavResponse>builder()
                .items(dtoList)
                .total(total)
                .page(pageNum)
                .size(pageSize)
                .build();
    }

    private String normalizeStatus(String status) {
        if (StringUtils.isBlank(status)) {
            return null;
        }
        if (!STRATEGY_STATUS_SET.contains(status)) {
            throw new BizException(ResponseCode.PARAM_ERROR, "status 仅支持 active/archived");
        }
        return status;
    }

    private String defaultBaseCurrency(String baseCurrency) {
        String value = StringUtils.defaultIfBlank(baseCurrency, PortfolioConstants.DEFAULT_BASE_CURRENCY);
        return value.toUpperCase();
    }

    private BigDecimal defaultCapitalBase(BigDecimal capitalBase) {
        BigDecimal value = capitalBase == null ? BigDecimal.ZERO : capitalBase;
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(ResponseCode.PARAM_ERROR, "capitalBase 不能为负数");
        }
        if (value.scale() > 2) {
            throw new BizException(ResponseCode.PARAM_ERROR, "capitalBase 最多两位小数");
        }
        return value;
    }

    private String defaultJson(String json) {
        return StringUtils.defaultIfBlank(json, "{}");
    }

    private void validateTarget(StrategyTargetUpsertItem item) {
        if (!SYMBOL_TYPES.contains(item.getSymbolType())) {
            throw new BizException(ResponseCode.PARAM_ERROR, "symbolType 仅支持 stock/etf/index/fund");
        }
        if (item.getTargetWeight().scale() > 6) {
            throw new BizException(ResponseCode.PARAM_ERROR, "targetWeight 最多六位小数");
        }
        if (item.getTargetWeight().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(ResponseCode.PARAM_ERROR, "targetWeight 不能为负数");
        }
    }

    private String buildArchivedName(String name, Long id) {
        String base = StringUtils.defaultString(name, "portfolio");
        String suffix = " [archived-" + (id == null ? "unknown" : id) + "]";
        int maxLen = 255;
        if (suffix.length() >= maxLen) {
            return suffix.substring(0, maxLen);
        }
        int available = maxLen - suffix.length();
        if (base.length() > available) {
            base = base.substring(0, available);
        }
        return base + suffix;
    }

    private PortfolioPo requireStrategyPortfolio(Long portfolioId, String userId) {
        PortfolioPo portfolio = portfolioMapper.findByIdAndUser(portfolioId, userId);
        if (portfolio == null || !"active".equals(portfolio.getStatus())) {
            throw new BizException(ResponseCode.DATA_NOT_FOUND, "组合不存在或已归档");
        }
        if (!"STRATEGY".equals(portfolio.getPortfolioType())) {
            throw new BizException(ResponseCode.PARAM_ERROR, "该组合不是策略类型");
        }
        return portfolio;
    }

    private StrategyResponse toResponse(StrategyDefinitionPo po, PortfolioPo portfolio) {
        return StrategyResponse.builder()
                .id(po.getId())
                .portfolioId(po.getPortfolioId())
                .userId(po.getUserId())
                .name(po.getName())
                .description(po.getDescription())
                .ruleJson(po.getRuleJson())
                .rebalanceRule(po.getRebalanceRule())
                .capitalBase(po.getCapitalBase())
                .startDate(po.getStartDate())
                .endDate(po.getEndDate())
                .status(po.getStatus())
                .baseCurrency(portfolio != null ? portfolio.getBaseCurrency() : null)
                .benchmarkSymbol(portfolio != null ? portfolio.getBenchmarkSymbol() : null)
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

    private StrategyTargetResponse toTargetResponse(StrategyTargetPo po) {
        return StrategyTargetResponse.builder()
                .id(po.getId())
                .strategyId(po.getStrategyId())
                .symbol(po.getSymbol())
                .symbolType(po.getSymbolType())
                .targetWeight(po.getTargetWeight())
                .effectiveDate(po.getEffectiveDate())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

    private StrategyBacktestRunResponse toRunResponse(StrategyBacktestRunPo po) {
        return StrategyBacktestRunResponse.builder()
                .id(po.getId())
                .strategyId(po.getStrategyId())
                .runTime(po.getRunTime())
                .startDate(po.getStartDate())
                .endDate(po.getEndDate())
                .status(po.getStatus())
                .build();
    }

    private StrategyNavResponse toNavResponse(StrategyNavPo po) {
        return StrategyNavResponse.builder()
                .id(po.getId())
                .runId(po.getRunId())
                .tradeDate(po.getTradeDate())
                .nav(po.getNav())
                .returnPct(po.getReturnPct())
                .benchmarkNav(po.getBenchmarkNav())
                .drawdown(po.getDrawdown())
                .build();
    }
}

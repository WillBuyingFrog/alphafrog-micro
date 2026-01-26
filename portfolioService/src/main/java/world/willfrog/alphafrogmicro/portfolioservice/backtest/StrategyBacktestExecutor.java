package world.willfrog.alphafrogmicro.portfolioservice.backtest;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.portfolioservice.domain.PortfolioPo;
import world.willfrog.alphafrogmicro.portfolioservice.domain.PricePoint;
import world.willfrog.alphafrogmicro.portfolioservice.domain.StrategyBacktestRunPo;
import world.willfrog.alphafrogmicro.portfolioservice.domain.StrategyDefinitionPo;
import world.willfrog.alphafrogmicro.portfolioservice.domain.StrategyNavPo;
import world.willfrog.alphafrogmicro.portfolioservice.domain.StrategyTargetPo;
import world.willfrog.alphafrogmicro.portfolioservice.mapper.PortfolioMapper;
import world.willfrog.alphafrogmicro.portfolioservice.mapper.StrategyBacktestRunMapper;
import world.willfrog.alphafrogmicro.portfolioservice.mapper.StrategyDefinitionMapper;
import world.willfrog.alphafrogmicro.portfolioservice.mapper.StrategyNavMapper;
import world.willfrog.alphafrogmicro.portfolioservice.mapper.StrategyPriceMapper;
import world.willfrog.alphafrogmicro.portfolioservice.mapper.StrategyTargetMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

@Slf4j
@Component
public class StrategyBacktestExecutor {

    private static final BigDecimal ZERO = new BigDecimal("0");
    private static final BigDecimal ONE = new BigDecimal("1");
    private static final int SCALE = 6;

    private final StrategyBacktestRunMapper runMapper;
    private final StrategyDefinitionMapper strategyDefinitionMapper;
    private final StrategyTargetMapper targetMapper;
    private final StrategyNavMapper navMapper;
    private final StrategyPriceMapper priceMapper;
    private final PortfolioMapper portfolioMapper;

    public StrategyBacktestExecutor(StrategyBacktestRunMapper runMapper,
                                    StrategyDefinitionMapper strategyDefinitionMapper,
                                    StrategyTargetMapper targetMapper,
                                    StrategyNavMapper navMapper,
                                    StrategyPriceMapper priceMapper,
                                    PortfolioMapper portfolioMapper) {
        this.runMapper = runMapper;
        this.strategyDefinitionMapper = strategyDefinitionMapper;
        this.targetMapper = targetMapper;
        this.navMapper = navMapper;
        this.priceMapper = priceMapper;
        this.portfolioMapper = portfolioMapper;
    }

    @Transactional
    public void execute(StrategyBacktestRunEvent event) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        // 只允许 pending 的任务进入执行，避免重复消费
        int updated = runMapper.markRunning(event.runId(), event.userId(), startedAt);
        if (updated == 0) {
            log.info("Backtest run already handled, skip runId={}", event.runId());
            return;
        }

        StrategyBacktestRunPo run = runMapper.findByIdAndUser(event.runId(), event.userId());
        if (run == null) {
            markFailed(event, "回测记录不存在");
            return;
        }
        StrategyDefinitionPo strategy = strategyDefinitionMapper.findByIdAndUser(event.strategyId(), event.userId());
        if (strategy == null) {
            markFailed(event, "策略不存在");
            return;
        }

        try {
            List<StrategyNavPo> navList = computeNav(strategy, run);
            if (navList.isEmpty()) {
                markFailed(event, "未生成任何净值数据");
                return;
            }
            navMapper.insertBatch(navList);
            runMapper.markFinished(event.runId(), event.userId(), "completed", OffsetDateTime.now(), null);
        } catch (Exception e) {
            log.error("Backtest run failed: runId={}", event.runId(), e);
            markFailed(event, e.getMessage());
        }
    }

    private void markFailed(StrategyBacktestRunEvent event, String message) {
        String error = StringUtils.abbreviate(message, 500);
        runMapper.markFinished(event.runId(), event.userId(), "failed", OffsetDateTime.now(), error);
    }

    private List<StrategyNavPo> computeNav(StrategyDefinitionPo strategy, StrategyBacktestRunPo run) {
        LocalDate startDate = run.getStartDate();
        LocalDate endDate = run.getEndDate();
        if (startDate == null || endDate == null) {
            throw new IllegalStateException("回测时间范围缺失");
        }
        // 读取目标权重与行情数据，生成交易日序列
        List<StrategyTargetPo> targets = targetMapper.listByStrategy(strategy.getId(), run.getUserId());
        if (targets == null || targets.isEmpty()) {
            throw new IllegalStateException("策略未配置目标权重");
        }

        Map<String, List<StrategyTargetPo>> targetsBySymbol = groupTargets(targets);
        Map<String, Map<LocalDate, BigDecimal>> priceSeries = loadPriceSeries(targetsBySymbol, startDate, endDate);
        if (priceSeries.isEmpty()) {
            throw new IllegalStateException("缺少行情数据");
        }

        NavigableSet<LocalDate> tradingDates = collectTradingDates(priceSeries);
        if (tradingDates.isEmpty()) {
            throw new IllegalStateException("缺少交易日数据");
        }

        Set<LocalDate> rebalanceDates = resolveRebalanceDates(new ArrayList<>(tradingDates), strategy.getRebalanceRule());
        BigDecimal capitalBase = strategy.getCapitalBase() == null || strategy.getCapitalBase().compareTo(ZERO) <= 0
                ? ONE
                : strategy.getCapitalBase();

        PortfolioPo portfolio = portfolioMapper.findByIdAndUser(strategy.getPortfolioId(), run.getUserId());
        String benchmarkSymbol = portfolio != null ? portfolio.getBenchmarkSymbol() : null;
        // 基准净值按同区间行情计算，缺失时返回 null
        BenchmarkTracker benchmarkTracker = buildBenchmarkTracker(benchmarkSymbol, startDate, endDate);
        Map<String, BigDecimal> lastPrices = new HashMap<>();
        Map<String, BigDecimal> holdings = new HashMap<>();
        boolean initialized = false;
        BigDecimal maxNav = null;
        List<StrategyNavPo> navList = new ArrayList<>();

        for (LocalDate tradeDate : tradingDates) {
            updateLastPrices(priceSeries, lastPrices, tradeDate);
            Map<String, BigDecimal> weights = resolveWeights(targetsBySymbol, tradeDate);
            if (weights.isEmpty()) {
                continue;
            }
            if (!hasAllPrices(weights.keySet(), lastPrices)) {
                continue;
            }

            boolean shouldRebalance = rebalanceDates.contains(tradeDate);
            if (!initialized) {
                shouldRebalance = true;
            }
            if (shouldRebalance) {
                // 按目标权重在再平衡日重新计算持仓数量
                holdings = allocateHoldings(weights, lastPrices, currentPortfolioValue(holdings, lastPrices, capitalBase, initialized));
                initialized = true;
            }

            if (!initialized) {
                continue;
            }

            BigDecimal portfolioValue = currentPortfolioValue(holdings, lastPrices, capitalBase, true);
            BigDecimal nav = scale(portfolioValue.divide(capitalBase, SCALE, RoundingMode.HALF_UP));
            if (maxNav == null || nav.compareTo(maxNav) > 0) {
                maxNav = nav;
            }
            BigDecimal returnPct = scale(nav.subtract(ONE));
            BigDecimal drawdown = maxNav == null
                    ? ZERO
                    : scale(nav.divide(maxNav, SCALE, RoundingMode.HALF_UP).subtract(ONE));
            BigDecimal benchmarkNav = benchmarkTracker.resolve(tradeDate);

            StrategyNavPo navPo = new StrategyNavPo();
            navPo.setRunId(run.getId());
            navPo.setUserId(run.getUserId());
            navPo.setTradeDate(tradeDate);
            navPo.setNav(nav);
            navPo.setReturnPct(returnPct);
            navPo.setBenchmarkNav(benchmarkNav);
            navPo.setDrawdown(drawdown);
            navList.add(navPo);
        }

        return navList;
    }

    private Map<String, List<StrategyTargetPo>> groupTargets(List<StrategyTargetPo> targets) {
        Map<String, List<StrategyTargetPo>> grouped = new HashMap<>();
        for (StrategyTargetPo target : targets) {
            if (StringUtils.isBlank(target.getSymbol())) {
                continue;
            }
            grouped.computeIfAbsent(target.getSymbol(), key -> new ArrayList<>()).add(target);
        }
        for (List<StrategyTargetPo> list : grouped.values()) {
            list.sort(Comparator.comparing(t -> t.getEffectiveDate() == null ? LocalDate.MIN : t.getEffectiveDate()));
        }
        return grouped;
    }

    private Map<String, BigDecimal> resolveWeights(Map<String, List<StrategyTargetPo>> targetsBySymbol, LocalDate date) {
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        BigDecimal total = ZERO;
        for (Map.Entry<String, List<StrategyTargetPo>> entry : targetsBySymbol.entrySet()) {
            StrategyTargetPo selected = selectTarget(entry.getValue(), date);
            if (selected == null || selected.getTargetWeight() == null) {
                continue;
            }
            BigDecimal weight = selected.getTargetWeight();
            if (weight.compareTo(ZERO) <= 0) {
                continue;
            }
            weights.put(entry.getKey(), weight);
            total = total.add(weight);
        }
        if (total.compareTo(ZERO) <= 0) {
            return Map.of();
        }
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : weights.entrySet()) {
            normalized.put(entry.getKey(), entry.getValue().divide(total, SCALE, RoundingMode.HALF_UP));
        }
        return normalized;
    }

    private StrategyTargetPo selectTarget(List<StrategyTargetPo> list, LocalDate date) {
        StrategyTargetPo selected = null;
        for (StrategyTargetPo item : list) {
            LocalDate effective = item.getEffectiveDate();
            if (effective != null && effective.isAfter(date)) {
                break;
            }
            selected = item;
        }
        return selected;
    }

    private Map<String, Map<LocalDate, BigDecimal>> loadPriceSeries(Map<String, List<StrategyTargetPo>> targetsBySymbol,
                                                                    LocalDate startDate,
                                                                    LocalDate endDate) {
        Map<String, Map<LocalDate, BigDecimal>> series = new HashMap<>();
        long startTs = DateConvertUtils.convertLocalDateToMsTimestamp(startDate);
        long endTs = DateConvertUtils.convertLocalDateToMsTimestamp(endDate);
        for (List<StrategyTargetPo> targets : targetsBySymbol.values()) {
            if (targets.isEmpty()) {
                continue;
            }
            StrategyTargetPo sample = targets.get(0);
            String symbol = sample.getSymbol();
            String symbolType = StringUtils.defaultString(sample.getSymbolType()).toLowerCase(Locale.ROOT);
            List<PricePoint> points = switch (symbolType) {
                case "index" -> priceMapper.listIndexDaily(symbol, startTs, endTs);
                case "fund" -> priceMapper.listFundNav(symbol, startTs, endTs);
                default -> priceMapper.listStockDaily(symbol, startTs, endTs);
            };
            Map<LocalDate, BigDecimal> map = toDateMap(points);
            if (!map.isEmpty()) {
                series.put(symbol, map);
            }
        }
        return series;
    }

    private Map<LocalDate, BigDecimal> toDateMap(List<PricePoint> points) {
        Map<LocalDate, BigDecimal> map = new HashMap<>();
        for (PricePoint point : points) {
            if (point.getTradeDate() == null || point.getClose() == null) {
                continue;
            }
            LocalDate date = DateConvertUtils.convertTimestampToLocalDate(point.getTradeDate());
            map.put(date, point.getClose());
        }
        return map;
    }

    private NavigableSet<LocalDate> collectTradingDates(Map<String, Map<LocalDate, BigDecimal>> priceSeries) {
        NavigableSet<LocalDate> dates = new TreeSet<>();
        for (Map<LocalDate, BigDecimal> series : priceSeries.values()) {
            dates.addAll(series.keySet());
        }
        return dates;
    }

    private Set<LocalDate> resolveRebalanceDates(List<LocalDate> tradingDates, String rule) {
        if (tradingDates.isEmpty()) {
            return Set.of();
        }
        if (StringUtils.isBlank(rule)) {
            return Set.of(tradingDates.get(0));
        }
        String normalized = rule.toLowerCase(Locale.ROOT);
        if (normalized.contains("monthly")) {
            Map<YearMonth, LocalDate> firstByMonth = new LinkedHashMap<>();
            for (LocalDate date : tradingDates) {
                firstByMonth.putIfAbsent(YearMonth.from(date), date);
            }
            return Set.copyOf(firstByMonth.values());
        }
        if (normalized.contains("weekly")) {
            Map<String, LocalDate> firstByWeek = new LinkedHashMap<>();
            WeekFields weekFields = WeekFields.ISO;
            for (LocalDate date : tradingDates) {
                String key = date.getYear() + "-" + date.get(weekFields.weekOfWeekBasedYear());
                firstByWeek.putIfAbsent(key, date);
            }
            return Set.copyOf(firstByWeek.values());
        }
        if (normalized.contains("daily")) {
            return Set.copyOf(tradingDates);
        }
        return Set.of(tradingDates.get(0));
    }

    private void updateLastPrices(Map<String, Map<LocalDate, BigDecimal>> priceSeries,
                                  Map<String, BigDecimal> lastPrices,
                                  LocalDate tradeDate) {
        for (Map.Entry<String, Map<LocalDate, BigDecimal>> entry : priceSeries.entrySet()) {
            BigDecimal price = entry.getValue().get(tradeDate);
            if (price != null) {
                lastPrices.put(entry.getKey(), price);
            }
        }
    }

    private boolean hasAllPrices(Set<String> symbols, Map<String, BigDecimal> lastPrices) {
        for (String symbol : symbols) {
            if (!lastPrices.containsKey(symbol)) {
                return false;
            }
        }
        return true;
    }

    private Map<String, BigDecimal> allocateHoldings(Map<String, BigDecimal> weights,
                                                     Map<String, BigDecimal> prices,
                                                     BigDecimal portfolioValue) {
        Map<String, BigDecimal> holdings = new HashMap<>();
        for (Map.Entry<String, BigDecimal> entry : weights.entrySet()) {
            BigDecimal price = prices.get(entry.getKey());
            if (price == null || price.compareTo(ZERO) <= 0) {
                continue;
            }
            BigDecimal allocation = portfolioValue.multiply(entry.getValue());
            BigDecimal quantity = allocation.divide(price, 10, RoundingMode.HALF_UP);
            holdings.put(entry.getKey(), quantity);
        }
        return holdings;
    }

    private BigDecimal currentPortfolioValue(Map<String, BigDecimal> holdings,
                                             Map<String, BigDecimal> prices,
                                             BigDecimal capitalBase,
                                             boolean initialized) {
        if (!initialized || holdings.isEmpty()) {
            return capitalBase;
        }
        BigDecimal total = ZERO;
        for (Map.Entry<String, BigDecimal> entry : holdings.entrySet()) {
            BigDecimal price = prices.get(entry.getKey());
            if (price == null) {
                continue;
            }
            total = total.add(price.multiply(entry.getValue()));
        }
        return total;
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BenchmarkTracker buildBenchmarkTracker(String benchmarkSymbol, LocalDate start, LocalDate end) {
        if (StringUtils.isBlank(benchmarkSymbol)) {
            return new BenchmarkTracker(Map.of());
        }
        long startTs = DateConvertUtils.convertLocalDateToMsTimestamp(start);
        long endTs = DateConvertUtils.convertLocalDateToMsTimestamp(end);
        List<PricePoint> points = priceMapper.listIndexDaily(benchmarkSymbol, startTs, endTs);
        if (points.isEmpty()) {
            points = priceMapper.listStockDaily(benchmarkSymbol, startTs, endTs);
        }
        if (points.isEmpty()) {
            points = priceMapper.listFundNav(benchmarkSymbol, startTs, endTs);
        }
        Map<LocalDate, BigDecimal> series = toDateMap(points);
        return new BenchmarkTracker(series);
    }

    private class BenchmarkTracker {
        private final Map<LocalDate, BigDecimal> series;
        private BigDecimal basePrice;
        private BigDecimal lastPrice;

        BenchmarkTracker(Map<LocalDate, BigDecimal> series) {
            this.series = series;
        }

        BigDecimal resolve(LocalDate tradeDate) {
            if (series.isEmpty()) {
                return null;
            }
            BigDecimal price = series.get(tradeDate);
            if (price != null) {
                lastPrice = price;
                if (basePrice == null) {
                    basePrice = price;
                }
            }
            if (lastPrice == null || basePrice == null) {
                return null;
            }
            return scale(lastPrice.divide(basePrice, SCALE, RoundingMode.HALF_UP));
        }
    }
}

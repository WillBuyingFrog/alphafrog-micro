package world.willfrog.alphafrogmicro.domestic.index.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.domestic.calendar.TradeCalendarDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.common.DataCompletenessDao;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@Slf4j
public class IndexDataCompletenessService {

    private static final String EXCHANGE_SSE = "SSE";
    private static final String CACHE_KEY_PATTERN = "index:completeness:%s:%d:%d";
    private static final Duration TTL_COMPLETE = Duration.ofDays(1);
    private static final Duration TTL_INCOMPLETE = Duration.ofMinutes(5);
    private static final Duration TTL_UPSTREAM_GAP = Duration.ofDays(1);

    private final TradeCalendarDao tradeCalendarDao;
    private final DataCompletenessDao dataCompletenessDao;
    private final RedisTemplate<String, Object> redisTemplate;

    public IndexDataCompletenessService(TradeCalendarDao tradeCalendarDao,
                                        DataCompletenessDao dataCompletenessDao,
                                        RedisTemplate<String, Object> redisTemplate) {
        this.tradeCalendarDao = tradeCalendarDao;
        this.dataCompletenessDao = dataCompletenessDao;
        this.redisTemplate = redisTemplate;
    }

    public IndexCompletenessResult evaluate(String tsCode, long startDateTimestamp, long endDateTimestamp) {
        String cacheKey = String.format(CACHE_KEY_PATTERN, tsCode, startDateTimestamp, endDateTimestamp);
        IndexCompletenessResult cached = readFromCache(cacheKey);
        long now = System.currentTimeMillis();

        // 若处于冷却中的上游缺口，直接返回缓存
        if (cached != null && cached.getStatus() == Status.UPSTREAM_GAP
                && cached.getNextRetryAtMs() != null && now < cached.getNextRetryAtMs()) {
            cached.setFromCache(true);
            return cached;
        }

        // 计算交易日与已存数据
        List<Long> tradingDates = safeList(tradeCalendarDao.getTradingDatesByRange(EXCHANGE_SSE, startDateTimestamp, endDateTimestamp));
        Set<Long> tradingDateSet = new HashSet<>(tradingDates);
        List<Long> existingDates = safeList(dataCompletenessDao.getExistingDates("alphafrog_index_daily", "ts_code", "trade_date", tsCode, startDateTimestamp, endDateTimestamp));
        Set<Long> existingDateSet = new HashSet<>(existingDates);

        Set<Long> missing = new HashSet<>(tradingDateSet);
        missing.removeAll(existingDateSet);

        IndexCompletenessResult result = new IndexCompletenessResult();
        result.setTsCode(tsCode);
        result.setStartDate(startDateTimestamp);
        result.setEndDate(endDateTimestamp);
        result.setExpectedTradingDays(tradingDateSet.size());
        result.setActualTradingDays(existingDateSet.size());
        result.setMissingCount(missing.size());
        result.setMissingDates(missing.stream().sorted().toList());
        result.setFromCache(false);

        boolean complete = missing.isEmpty();
        result.setComplete(complete);

        Status previousStatus = cached != null ? cached.getStatus() : null;

        if (complete) {
            result.setStatus(Status.COMPLETE);
            result.setUpstreamGap(false);
            result.setNextRetryAtMs(null);
            writeToCache(cacheKey, result, TTL_COMPLETE);
            return result;
        }

        // 不完整：若前次即为不完整，则认定为上游缺口，进入冷却
        if (previousStatus == Status.INCOMPLETE || previousStatus == Status.UPSTREAM_GAP) {
            result.setStatus(Status.UPSTREAM_GAP);
            result.setUpstreamGap(true);
            result.setNextRetryAtMs(now + TTL_UPSTREAM_GAP.toMillis());
            writeToCache(cacheKey, result, TTL_UPSTREAM_GAP);
        } else {
            result.setStatus(Status.INCOMPLETE);
            result.setUpstreamGap(false);
            result.setNextRetryAtMs(null);
            writeToCache(cacheKey, result, TTL_INCOMPLETE);
        }

        return result;
    }

    private IndexCompletenessResult readFromCache(String cacheKey) {
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof IndexCompletenessResult) {
                return (IndexCompletenessResult) cached;
            }
        } catch (Exception e) {
            log.warn("Read completeness cache failed, key={}", cacheKey, e);
        }
        return null;
    }

    private void writeToCache(String cacheKey, IndexCompletenessResult result, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(cacheKey, result, ttl);
        } catch (Exception e) {
            log.warn("Write completeness cache failed, key={}, status={}", cacheKey, result.getStatus(), e);
        }
    }

    private List<Long> safeList(List<Long> input) {
        if (input == null) {
            return new ArrayList<>();
        }
        return input.stream().filter(Objects::nonNull).toList();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndexCompletenessResult implements Serializable {
        private String tsCode;
        private long startDate;
        private long endDate;
        private boolean complete;
        private int expectedTradingDays;
        private int actualTradingDays;
        private int missingCount;
        private List<Long> missingDates;
        private boolean upstreamGap;
        private Long nextRetryAtMs;
        private boolean fromCache;
        private Status status;
    }

    public enum Status {
        COMPLETE,
        INCOMPLETE,
        UPSTREAM_GAP
    }
}

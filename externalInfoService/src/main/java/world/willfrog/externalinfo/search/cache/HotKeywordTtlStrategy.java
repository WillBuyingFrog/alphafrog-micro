package world.willfrog.externalinfo.search.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * 热点缓存 TTL 策略计算器。
 * 结合确定性护栏规则与小模型建议，最终取 min(护栏, 建议)。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HotKeywordTtlStrategy {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 0);
    private static final int DEFAULT_TTL_SECONDS = 4 * 3600;
    private static final int HISTORY_TTL_SECONDS = 24 * 3600;

    /**
     * 计算热点缓存过期时间（秒）。
     * 最终取 min(确定性护栏, 小模型建议)。
     *
     * @param query 查询文本
     * @param scene 场景标识
     * @param slots 槽位提取结果
     * @return TTL（秒）
     */
    public long computeTtl(String query, String scene, SlotResult slots) {
        long deterministic = computeDeterministicTtl(query, scene, slots);
        long modelSuggestion = askSmallModelForTtl(query, scene);
        return Math.min(deterministic, modelSuggestion);
    }

    /**
     * 纯规则计算 TTL（确定性护栏）。
     * 规则优先级：
     * 1. 新闻类（scene=news 或 query 含"新闻"）：到次日 04:00（Asia/Shanghai）
     * 2. 盘中实时类（query 含"今天"、"实时"、"现在"）：到当日收盘后 2 小时（A股收盘 15:00，即 17:00）
     * 3. 历史类（query 含"去年"、"前年"、"历史"）：固定 24 小时
     * 4. 默认：4 小时
     */
    public long computeDeterministicTtl(String query, String scene, SlotResult slots) {
        String q = query == null ? "" : query;
        String s = scene == null ? "" : scene;

        // 新闻类
        if ("news".equalsIgnoreCase(s) || q.contains("新闻")) {
            return ttlToNext4AM();
        }

        // 盘中实时类
        if (q.contains("今天") || q.contains("实时") || q.contains("现在")) {
            return ttlToMarketClosePlus2Hours();
        }

        // 历史类
        if (q.contains("去年") || q.contains("前年") || q.contains("历史")) {
            return HISTORY_TTL_SECONDS;
        }

        return DEFAULT_TTL_SECONDS;
    }

    /**
     * 小模型建议 TTL。
     * 预留接口，当前返回 Long.MAX_VALUE 以使 min(护栏, 建议) = 护栏。
     * P2 后期可接入 LLM 进行智能推断。
     */
    public long askSmallModelForTtl(String query, String scene) {
        // 预留：后续可调用小模型推断 TTL
        return Long.MAX_VALUE;
    }

    /**
     * 计算到次日 04:00（Asia/Shanghai）的秒数。
     */
    private long ttlToNext4AM() {
        ZonedDateTime now = ZonedDateTime.now(SHANGHAI);
        ZonedDateTime target = now.with(LocalTime.of(4, 0));
        if (!now.isBefore(target)) {
            target = target.plusDays(1);
        }
        return Duration.between(now, target).getSeconds();
    }

    /**
     * 计算到当日收盘后 2 小时（即 17:00 Asia/Shanghai）的秒数。
     * 若当前已过 17:00，则取次日 17:00。
     */
    private long ttlToMarketClosePlus2Hours() {
        ZonedDateTime now = ZonedDateTime.now(SHANGHAI);
        ZonedDateTime target = now.with(MARKET_CLOSE_TIME).plusHours(2);
        if (!now.isBefore(target)) {
            target = target.plusDays(1);
        }
        return Duration.between(now, target).getSeconds();
    }

    /**
     * 槽位提取结果（P1 接入 SlotExtractor 后可在 slot 包下统一替换）。
     */
    public record SlotResult(Map<String, String> slots) {}
}

package world.willfrog.externalinfo.search.slot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户查询中提取硬槽位。
 * 以规则（正则 + 关键词词典）为主，不调用 LLM。
 */
@Component
@Slf4j
public class SlotExtractor {

    // A 股代码正则：6 位数字 + .SH/.SZ/.BJ 等
    private static final Pattern ASSET_CODE_A_SHARE = Pattern.compile("\\b\\d{6}\\.[A-Z]{2}\\b");
    // 美股代码正则：1-5 位大写字母（辅以常见词典过滤，降低误杀）
    private static final Pattern ASSET_CODE_US = Pattern.compile("\\b[A-Z]{1,5}\\b");

    // 时间范围正则
    private static final Pattern TIME_RANGE_PATTERN = Pattern.compile(
            "去年|前年|今年|上年|本周|这周|上周|下周|今天|今日|昨天|昨日|前天|后天"
                    + "|近[一二三四五六七八九十\\d]+[天周月年]"
                    + "|\\d{4}年"
                    + "|\\d{1,2}月\\d{1,2}日"
                    + "|近\\d+[天周月年]"
    );

    // 数值条件正则：匹配包含数字+%/亿/万 的短语
    private static final Pattern NUMERIC_CONDITION_PATTERN = Pattern.compile(
            "(涨幅|市值|市盈率|市净率|成交量|成交额|价格|收益).{0,10}[大于|超过|低于|小于|等于|≥|≤|>|<|=].{0,10}\\d+[\\.\\d]*[%亿万亿万]?",
            Pattern.CASE_INSENSITIVE
    );

    // 常见资产名称词典（中文 + 英文）
    private static final Set<String> ASSET_NAME_DICT = Set.of(
            "茅台", "贵州茅台", "工商银行", "工行", "建设银行", "建行", "农业银行", "农行",
            "中国银行", "招行", "招商银行", "中国平安", "平安", "比亚迪", "宁德时代", "腾讯",
            "阿里巴巴", "阿里", "京东", "美团", "百度", "Apple", "AAPL", "Tesla", "TSLA",
            "Google", "GOOGL", "Amazon", "AMZN", "Microsoft", "MSFT", "Meta", "META",
            "Nvidia", "NVDA", "AMD", "Intel", "INTC", "Netflix", "NFLX"
    );

    // 市场范围关键词
    private static final Set<String> MARKET_SCOPE_DICT = Set.of(
            "A股", "美股", "港股", "港股通", "四大行", "银行股", "科技股", "创业板",
            "科创板", "上证", "深证", "沪深", "中证", "纳斯达克", "NYSE", "标普", "道琼斯"
    );

    // 时间范围归一化映射
    private static final Map<String, String> TIME_NORMALIZE_MAP = Map.ofEntries(
            Map.entry("去年", "上年全年"),
            Map.entry("前年", "前年全年"),
            Map.entry("这周", "本周"),
            Map.entry("本周", "本周"),
            Map.entry("今天", "今日"),
            Map.entry("今日", "今日"),
            Map.entry("昨天", "昨日"),
            Map.entry("昨日", "昨日")
    );

    /**
     * 从 query 中提取所有槽位。
     */
    public SlotResult extract(String query) {
        if (query == null || query.isBlank()) {
            return new SlotResult("", "", "", List.of(), "");
        }

        try {
            String assetCode = extractAssetCode(query);
            String assetName = extractAssetName(query);
            String timeRange = extractTimeRange(query);
            List<String> numericConditions = extractNumericConditions(query);
            String marketScope = extractMarketScope(query);

            return new SlotResult(assetCode, assetName, timeRange, numericConditions, marketScope);
        } catch (Exception e) {
            log.error("槽位提取失败, query={}", query, e);
            return new SlotResult("", "", "", List.of(), "");
        }
    }

    /**
     * 生成槽位签名，格式：asset=xxx|time_range=xxx|market=xxx。
     * 时间范围会归一化到粗粒度。
     */
    public String computeSlotSignature(SlotResult slots) {
        String asset = (slots.assetCode() != null && !slots.assetCode().isEmpty())
                ? slots.assetCode()
                : (slots.assetName() != null ? slots.assetName() : "");
        String time = normalizeTimeRange(slots.timeRange());
        String market = slots.marketScope() != null ? slots.marketScope() : "";
        return "asset=" + asset + "|time_range=" + time + "|market=" + market;
    }

    private String extractAssetCode(String query) {
        Matcher m = ASSET_CODE_A_SHARE.matcher(query);
        if (m.find()) {
            return m.group();
        }
        // 美股代码：仅在命中常见词典时返回，降低误杀率
        Matcher us = ASSET_CODE_US.matcher(query);
        while (us.find()) {
            String code = us.group();
            if (ASSET_NAME_DICT.contains(code)) {
                return code;
            }
        }
        return "";
    }

    private String extractAssetName(String query) {
        // 优先匹配较长的名称，避免短子串优先命中
        return ASSET_NAME_DICT.stream()
                .filter(name -> query.contains(name))
                .max(Comparator.comparingInt(String::length))
                .orElse("");
    }

    private String extractTimeRange(String query) {
        Matcher m = TIME_RANGE_PATTERN.matcher(query);
        if (m.find()) {
            return m.group();
        }
        return "";
    }

    private List<String> extractNumericConditions(String query) {
        List<String> list = new ArrayList<>();
        Matcher m = NUMERIC_CONDITION_PATTERN.matcher(query);
        while (m.find()) {
            list.add(m.group());
        }
        return list;
    }

    private String extractMarketScope(String query) {
        return MARKET_SCOPE_DICT.stream()
                .filter(key -> query.contains(key))
                .max(Comparator.comparingInt(String::length))
                .orElse("");
    }

    private String normalizeTimeRange(String timeRange) {
        if (timeRange == null || timeRange.isEmpty()) {
            return "";
        }
        return TIME_NORMALIZE_MAP.getOrDefault(timeRange, timeRange);
    }

    /**
     * 槽位提取结果。
     */
    public record SlotResult(
            String assetCode,
            String assetName,
            String timeRange,
            List<String> numericConditions,
            String marketScope
    ) {
    }
}

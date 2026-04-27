package world.willfrog.externalinfo.search.slot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 查询意图模板生成器。
 * 去除停用词并将槽位值替换为占位符。
 */
@Component
@Slf4j
public class QueryCanonicalizer {

    private static final List<String> STOP_WORDS;

    static {
        // 按长度降序排列，避免短停用词提前破坏长停用词
        Set<String> stopSet = Set.of(
                "的", "了", "吗", "呢", "吧", "帮我", "查一下", "查询", "一下", "请",
                "请问", "给我", "我想", "知道", "关于", "最近", "想要", "需要",
                "看看", "搜一下", "搜索", "找一下", "查找", "帮我查", "给我查"
        );
        List<String> list = new ArrayList<>(stopSet);
        list.sort(Comparator.comparingInt(String::length).reversed());
        STOP_WORDS = List.copyOf(list);
    }

    /**
     * 将原始 query 规范化为意图模板。
     *
     * @param query 原始查询
     * @param slots 已提取的槽位
     * @return 意图模板字符串
     */
    public String canonicalize(String query, SlotExtractor.SlotResult slots) {
        if (query == null || query.isBlank()) {
            return "";
        }
        try {
            String text = query.trim();

            // 1. 去除停用词（替换为空格）
            for (String stop : STOP_WORDS) {
                text = text.replace(stop, " ");
            }

            // 2. 槽位替换为占位符（先替换较长的，减少残留）
            if (slots.assetCode() != null && !slots.assetCode().isEmpty()) {
                text = text.replace(slots.assetCode(), "{asset}");
            }
            if (slots.assetName() != null && !slots.assetName().isEmpty()) {
                text = text.replace(slots.assetName(), "{asset}");
            }
            if (slots.timeRange() != null && !slots.timeRange().isEmpty()) {
                text = text.replace(slots.timeRange(), "{time_range}");
            }
            if (slots.marketScope() != null && !slots.marketScope().isEmpty()) {
                text = text.replace(slots.marketScope(), "{market}");
            }

            // 3. 去除多余空格
            text = text.replaceAll("\\s+", " ").trim();

            return text;
        } catch (Exception e) {
            log.error("查询规范化失败, query={}", query, e);
            return query.trim();
        }
    }
}

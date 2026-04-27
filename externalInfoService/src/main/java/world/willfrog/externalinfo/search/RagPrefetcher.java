package world.willfrog.externalinfo.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.externalinfo.idl.RagSearchRequest;
import world.willfrog.alphafrogmicro.externalinfo.idl.RagSearchResponse;
import world.willfrog.alphafrogmicro.externalinfo.idl.RagSearchResultItem;
import world.willfrog.externalinfo.retrieval.RagSearchServiceImpl;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 预检器。
 * 在联网搜索前查询本地 RAG，根据相关性判定结果决定是否降级联网搜索强度。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RagPrefetcher {

    private final RagSearchServiceImpl ragSearchService;

    /**
     * 默认预检入口。
     */
    public RagPrefetchResult prefetch(String query, String scene) {
        return prefetch(query, scene, null);
    }

    /**
     * 带当前 strength 的预检入口。
     *
     * @param query           用户查询（已规范化）
     * @param scene           场景
     * @param currentStrength 当前 strength，为空时不会触发降级
     */
    public RagPrefetchResult prefetch(String query, String scene, String currentStrength) {
        try {
            RagSearchRequest request = RagSearchRequest.newBuilder()
                    .setQueryText(query)
                    .setTopK(3)
                    .build();

            RagSearchResponse response = ragSearchService.ragSearch(request);
            if (response == null || response.getItemsCount() == 0) {
                return RagPrefetchResult.noHit();
            }

            List<RagSearchResultItem> items = response.getItemsList();
            String ragSummary = buildSummary(items);
            float relevanceScore = evaluateRelevance(query, items);

            // 预留 LLM 相关性判定接口（当前使用规则兜底）
            // float llmScore = evaluateRelevanceByLlm(query, items);

            String adjustedStrength = computeAdjustedStrength(currentStrength, relevanceScore);

            return new RagPrefetchResult(true, relevanceScore, ragSummary, adjustedStrength);
        } catch (Exception e) {
            log.error("RAG 预检失败, query={}", query, e);
            return RagPrefetchResult.noHit();
        }
    }

    /**
     * 构建 RAG 结果压缩摘要（取前 3 条 title + chunk_text 摘要）。
     */
    private String buildSummary(List<RagSearchResultItem> items) {
        StringBuilder sb = new StringBuilder();
        int count = Math.min(items.size(), 3);
        for (int i = 0; i < count; i++) {
            RagSearchResultItem item = items.get(i);
            sb.append(i + 1).append(". ");
            if (!item.getTitle().isEmpty()) {
                sb.append(item.getTitle()).append(": ");
            }
            String chunk = item.getChunkText();
            if (chunk.length() > 120) {
                chunk = chunk.substring(0, 120) + "...";
            }
            sb.append(chunk);
            if (i < count - 1) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }

    /**
     * 规则式相关性判定：检查 query 关键词是否在 RAG 结果的 title/chunk_text 中出现。
     * 同时结合向量相似度最高分做平滑，避免纯文本匹配过于稀疏。
     */
    private float evaluateRelevance(String query, List<RagSearchResultItem> items) {
        if (query == null || query.isBlank() || items == null || items.isEmpty()) {
            return 0f;
        }

        Set<String> keywords = extractKeywords(query);
        if (keywords.isEmpty()) {
            return 0f;
        }

        int matched = 0;
        for (String kw : keywords) {
            boolean found = false;
            for (RagSearchResultItem item : items) {
                String text = (item.getTitle() + " " + item.getChunkText()).toLowerCase();
                if (text.contains(kw.toLowerCase())) {
                    found = true;
                    break;
                }
            }
            if (found) {
                matched++;
            }
        }

        float baseScore = (float) matched / keywords.size();

        float maxVectorScore = 0f;
        for (RagSearchResultItem item : items) {
            if (item.getScore() > maxVectorScore) {
                maxVectorScore = item.getScore();
            }
        }

        // 文本匹配占 70%，向量分数占 30%，上限 1.0
        return Math.min(1.0f, baseScore * 0.7f + maxVectorScore * 0.3f);
    }

    /**
     * 预留：LLM 相关性判定接口。
     * 后续可接入小型 LLM endpoint（配置读取自 SearchLlmProperties）。
     */
    @SuppressWarnings("unused")
    private float evaluateRelevanceByLlm(String query, List<RagSearchResultItem> items) {
        // TODO: 调用小型 LLM，注入用户全局画像（时区=Asia/Shanghai、语言=zh），解析 0-1 浮点数得分
        return 0f;
    }

    /**
     * 根据相关性得分和当前 strength 计算调整后的 strength。
     */
    private String computeAdjustedStrength(String currentStrength, float relevanceScore) {
        if (relevanceScore < 0.7f || currentStrength == null || currentStrength.isBlank()) {
            return null;
        }
        return switch (currentStrength.trim().toLowerCase()) {
            case "deep" -> "standard";
            case "standard" -> "fast";
            default -> null;
        };
    }

    /**
     * 提取 query 中的关键词（中文连续片段或英文/数字单词）。
     */
    private Set<String> extractKeywords(String query) {
        Set<String> keywords = new LinkedHashSet<>();
        Pattern pattern = Pattern.compile("[\\u4e00-\\u9fa5]+|[a-zA-Z0-9]+");
        Matcher matcher = pattern.matcher(query);
        while (matcher.find()) {
            String word = matcher.group();
            // 过滤单字或单字母噪音
            if (word.length() >= 2) {
                keywords.add(word.toLowerCase());
            }
        }
        return keywords;
    }

    /**
     * RAG 预检结果。
     */
    public record RagPrefetchResult(
            boolean used,
            float relevanceScore,
            String ragSummary,
            String adjustedStrength
    ) {
        public static RagPrefetchResult noHit() {
            return new RagPrefetchResult(false, 0f, "", null);
        }
    }
}

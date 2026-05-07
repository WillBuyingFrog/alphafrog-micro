package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import world.willfrog.agent.workflow.CompletedTodoInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从已完成任务输出中聚合搜索引用，生成最终回答可使用的统一 citation map。
 */
@Service
@RequiredArgsConstructor
public class AgentCitationService {

    private static final int MAX_CITATIONS = 50;
    private static final int MAX_TITLE_LENGTH = 160;

    private final ObjectMapper objectMapper;

    public CitationMap buildCitationMap(List<CompletedTodoInfo> completedTodos) {
        if (completedTodos == null || completedTodos.isEmpty()) {
            return CitationMap.empty();
        }
        LinkedHashMap<String, Citation> byUrl = new LinkedHashMap<>();
        for (CompletedTodoInfo todo : completedTodos) {
            collectFromTodo(todo, byUrl);
            if (byUrl.size() >= MAX_CITATIONS) {
                break;
            }
        }
        if (byUrl.isEmpty()) {
            return CitationMap.empty();
        }
        List<Citation> citations = new ArrayList<>();
        int index = 1;
        for (Citation citation : byUrl.values()) {
            if (index > MAX_CITATIONS) {
                break;
            }
            citations.add(citation.withIndex(index++));
        }
        return new CitationMap(citations);
    }

    public String buildPromptBlock(CitationMap citationMap) {
        if (citationMap == null || citationMap.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder();
        block.append("\n\n可引用来源（只能使用下列编号，不要自行编造编号）：\n");
        for (Citation citation : citationMap.citations()) {
            block.append("[")
                    .append(citation.index())
                    .append("] ")
                    .append(citation.title().isBlank() ? citation.url() : citation.title())
                    .append(" - ")
                    .append(citation.url());
            if (citation.hasQualityRisk()) {
                block.append("（相关性需谨慎");
                if (!citation.relevanceWarning().isBlank()) {
                    block.append("：").append(citation.relevanceWarning());
                }
                block.append("）");
            }
            block.append("\n");
        }
        block.append("若回答使用了这些搜索证据，请在对应句子后标注来源编号，如 [1]。");
        return block.toString();
    }

    public Map<String, Object> toSnapshotMap(CitationMap citationMap) {
        if (citationMap == null || citationMap.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Citation citation : citationMap.citations()) {
            rows.add(citation.toMap());
        }
        return Map.of("citations", rows);
    }

    public CitationMap fromSnapshotMap(Object snapshotCitationMap) {
        if (snapshotCitationMap == null) {
            return CitationMap.empty();
        }
        try {
            JsonNode root = objectMapper.valueToTree(snapshotCitationMap);
            JsonNode citations = root == null ? null : root.get("citations");
            if (citations == null && root != null && root.isArray()) {
                citations = root;
            }
            if (citations == null || !citations.isArray()) {
                return CitationMap.empty();
            }
            List<Citation> rows = new ArrayList<>();
            for (JsonNode item : citations) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                int index = item.path("index").asInt(rows.size() + 1);
                rows.add(new Citation(
                        index,
                        item.path("originalIndex").asInt(item.path("original_index").asInt(0)),
                        truncate(text(item, "title"), MAX_TITLE_LENGTH),
                        text(item, "url"),
                        text(item, "sourceTodoId").isBlank() ? text(item, "source_todo_id") : text(item, "sourceTodoId"),
                        item.path("entityMatch").isMissingNode() || item.path("entityMatch").asBoolean(true),
                        item.path("relevanceJudged").isMissingNode() ? Boolean.TRUE : item.path("relevanceJudged").asBoolean(),
                        text(item, "relevanceWarning").isBlank() ? text(item, "relevance_warning") : text(item, "relevanceWarning")
                ));
                if (rows.size() >= MAX_CITATIONS) {
                    break;
                }
            }
            return rows.isEmpty() ? CitationMap.empty() : new CitationMap(rows);
        } catch (Exception ignored) {
            return CitationMap.empty();
        }
    }

    public CitationMap buildCitationMapFromSnapshotCompletedItems(Object completedItems) {
        if (completedItems == null) {
            return CitationMap.empty();
        }
        try {
            JsonNode root = objectMapper.valueToTree(completedItems);
            if (root == null || !root.isArray()) {
                return CitationMap.empty();
            }
            List<CompletedTodoInfo> todos = new ArrayList<>();
            for (JsonNode item : root) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                todos.add(CompletedTodoInfo.builder()
                        .todoId(firstText(item, "todoId", "todo_id", "id"))
                        .description(firstText(item, "description"))
                        .summary(firstText(item, "summary"))
                        .output(firstText(item, "output"))
                        .build());
            }
            return buildCitationMap(todos);
        } catch (Exception ignored) {
            return CitationMap.empty();
        }
    }

    private void collectFromTodo(CompletedTodoInfo todo, LinkedHashMap<String, Citation> byUrl) {
        if (todo == null || todo.getOutput() == null || todo.getOutput().isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(todo.getOutput());
            collectCitationArrays(root, todo, byUrl);
        } catch (Exception ignored) {
            // 普通文本输出不是错误，直接跳过。
        }
    }

    private void collectCitationArrays(JsonNode node, CompletedTodoInfo todo, LinkedHashMap<String, Citation> byUrl) {
        if (node == null || node.isNull() || byUrl.size() >= MAX_CITATIONS) {
            return;
        }
        if (node.isObject()) {
            JsonNode citations = node.get("citations");
            if (citations != null && citations.isArray()) {
                collectCitations(citations, todo, byUrl);
            }
            node.fields().forEachRemaining(entry -> {
                if (byUrl.size() < MAX_CITATIONS) {
                    collectCitationArrays(entry.getValue(), todo, byUrl);
                }
            });
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (byUrl.size() >= MAX_CITATIONS) {
                    break;
                }
                collectCitationArrays(child, todo, byUrl);
            }
        }
    }

    private void collectCitations(JsonNode citations, CompletedTodoInfo todo, LinkedHashMap<String, Citation> byUrl) {
        for (JsonNode item : citations) {
            if (byUrl.size() >= MAX_CITATIONS) {
                break;
            }
            if (item == null || !item.isObject()) {
                continue;
            }
            String url = text(item, "url");
            String key = normalizeUrlKey(url);
            if (key.isBlank() || byUrl.containsKey(key)) {
                continue;
            }
            Citation citation = new Citation(
                    0,
                    item.path("index").isInt() ? item.path("index").asInt() : 0,
                    truncate(text(item, "title"), MAX_TITLE_LENGTH),
                    url.trim(),
                    todo.getTodoId() == null ? "" : todo.getTodoId(),
                    item.path("entityMatch").isMissingNode() || item.path("entityMatch").asBoolean(true),
                    item.path("relevanceJudged").isMissingNode() ? Boolean.TRUE : item.path("relevanceJudged").asBoolean(),
                    text(item, "relevanceWarning")
            );
            byUrl.put(key, citation);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String normalizeUrlKey(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String normalized = url.trim();
        int fragment = normalized.indexOf('#');
        if (fragment >= 0) {
            normalized = normalized.substring(0, fragment);
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength);
    }

    public record CitationMap(List<Citation> citations) {
        public static CitationMap empty() {
            return new CitationMap(List.of());
        }

        public boolean isEmpty() {
            return citations == null || citations.isEmpty();
        }

        public Citation byIndex(int index) {
            if (citations == null) {
                return null;
            }
            for (Citation citation : citations) {
                if (citation.index() == index) {
                    return citation;
                }
            }
            return null;
        }
    }

    public record Citation(
            int index,
            int originalIndex,
            String title,
            String url,
            String sourceTodoId,
            boolean entityMatch,
            boolean relevanceJudged,
            String relevanceWarning
    ) {
        Citation withIndex(int newIndex) {
            return new Citation(
                    newIndex,
                    originalIndex,
                    title == null ? "" : title,
                    url == null ? "" : url,
                    sourceTodoId == null ? "" : sourceTodoId,
                    entityMatch,
                    relevanceJudged,
                    relevanceWarning == null ? "" : relevanceWarning
            );
        }

        boolean hasQualityRisk() {
            return !entityMatch || !relevanceJudged || (relevanceWarning != null && !relevanceWarning.isBlank());
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("index", index);
            map.put("originalIndex", originalIndex);
            map.put("title", title == null ? "" : title);
            map.put("url", url == null ? "" : url);
            map.put("sourceTodoId", sourceTodoId == null ? "" : sourceTodoId);
            map.put("entityMatch", entityMatch);
            map.put("relevanceJudged", relevanceJudged);
            map.put("relevanceWarning", relevanceWarning == null ? "" : relevanceWarning);
            return map;
        }
    }
}

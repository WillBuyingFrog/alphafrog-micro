package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchEvidenceJudgeService {

    public static final String STAGE = "search_evidence_judge";
    private static final int MAX_TITLE_CHARS = 200;
    private static final int MAX_SNIPPET_CHARS = 600;
    private static final int MAX_URL_CHARS = 400;

    private final ObjectMapper objectMapper;
    private final AgentAiServiceFactory aiServiceFactory;
    private final JudgeModelSelectorService judgeModelSelectorService;

    public JudgeResult judge(String query,
                             List<String> requestedEntities,
                             List<Map<String, Object>> hits,
                             List<Map<String, Object>> citations) {
        List<Map<String, Object>> safeHits = hits == null ? List.of() : hits;
        List<Map<String, Object>> safeCitations = citations == null ? List.of() : citations;
        if (safeHits.isEmpty() && safeCitations.isEmpty()) {
            return new JudgeResult(true, "", List.of(), List.of());
        }

        SelectionAndModel selected = selectModel();
        if (selected == null) {
            return failOpen("NO_JUDGE_ROUTE", safeHits.size(), safeCitations.size());
        }

        String previousPhase = AgentContext.getPhase();
        String previousStage = AgentContext.getStage();
        try {
            AgentContext.setPhase(AgentObservabilityService.PHASE_SUMMARIZING);
            AgentContext.setStage(STAGE);
            ChatResponse response = selected.model().chat(buildMessages(query, requestedEntities, safeHits, safeCitations));
            String text = response.aiMessage() == null ? "" : nvl(response.aiMessage().text());
            JsonNode root = parseJson(text);
            Validation validation = validate(root, safeHits.size(), safeCitations.size());
            if (!validation.valid()) {
                return failOpen(validation.error(), safeHits.size(), safeCitations.size());
            }
            return new JudgeResult(
                    true,
                    "",
                    parseItems(root.path("hits"), safeHits.size(), ""),
                    parseItems(root.path("citations"), safeCitations.size(), "")
            );
        } catch (Exception e) {
            log.warn("Search evidence judge failed: {}", e.getMessage());
            return failOpen("JUDGE_CALL_FAILED: " + nvl(e.getMessage()), safeHits.size(), safeCitations.size());
        } finally {
            restorePhaseAndStage(previousPhase, previousStage);
        }
    }

    private SelectionAndModel selectModel() {
        for (JudgeModelSelectorService.Selection candidate : judgeModelSelectorService.selectCandidates()) {
            try {
                AgentLlmResolver.ResolvedLlm resolved = aiServiceFactory.resolveLlm(
                        candidate.getEndpointName(), candidate.getModelName());
                ChatModel model = aiServiceFactory.buildChatModelWithProviderOrderAndTemperature(
                        resolved, List.of(), 0.0D);
                return new SelectionAndModel(candidate, model);
            } catch (Exception e) {
                log.warn("Init search evidence judge model failed: endpoint={}, model={}, err={}",
                        candidate.getEndpointName(), candidate.getModelName(), e.getMessage());
            }
        }
        return null;
    }

    private List<ChatMessage> buildMessages(String query,
                                            List<String> requestedEntities,
                                            List<Map<String, Object>> hits,
                                            List<Map<String, Object>> citations) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", nvl(query));
        payload.put("requested_entities", requestedEntities == null ? List.of() : requestedEntities);
        payload.put("hits", compactItems(hits, true));
        payload.put("citations", compactItems(citations, false));
        return List.of(
                new SystemMessage("""
                        你是搜索证据相关性 judge。只判断每条搜索结果是否围绕用户明确问题和明确实体。
                        不要回答用户问题，不要改写搜索结果，不要删除结果。
                        只输出 JSON，格式为:
                        {"hits":[{"position":0,"entityMatch":true,"matchedEntities":[],"outOfScopeEntities":[],"relevanceWarning":""}],"citations":[{"position":0,"entityMatch":true,"matchedEntities":[],"outOfScopeEntities":[],"relevanceWarning":""}]}
                        position 必须从 0 开始，并与输入数组一一对应。
                        entityMatch 表示结果是否命中用户明确实体或用户未给出明确实体时是否围绕查询主题。
                        outOfScopeEntities 只列出明显偏离用户明确实体范围的实体。
                        relevanceWarning 为空字符串表示无警告；存在偏题、实体错配或证据不足时用简短中文说明。
                        """),
                new UserMessage(writeJson(payload))
        );
    }

    private List<Map<String, Object>> compactItems(List<Map<String, Object>> items, boolean includeSnippet) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> source = items.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("position", i);
            row.put("title", truncate(stringValue(source.get("title")), MAX_TITLE_CHARS));
            row.put("url", truncate(stringValue(source.get("url")), MAX_URL_CHARS));
            if (includeSnippet) {
                row.put("snippet", truncate(stringValue(source.get("snippet")), MAX_SNIPPET_CHARS));
                row.put("source", truncate(stringValue(source.get("source")), MAX_TITLE_CHARS));
                row.put("published_date", truncate(stringValue(source.get("published_date")), MAX_TITLE_CHARS));
            }
            out.add(row);
        }
        return out;
    }

    private Validation validate(JsonNode root, int hitCount, int citationCount) {
        if (root == null || !root.isObject()) {
            return Validation.invalid("JUDGE_BAD_JSON");
        }
        if (!root.path("hits").isArray() || !root.path("citations").isArray()) {
            return Validation.invalid("JUDGE_MISSING_ARRAYS");
        }
        if (root.path("hits").size() != hitCount || root.path("citations").size() != citationCount) {
            return Validation.invalid("JUDGE_COUNT_MISMATCH");
        }
        if (!positionsValid(root.path("hits")) || !positionsValid(root.path("citations"))) {
            return Validation.invalid("JUDGE_POSITION_MISMATCH");
        }
        return Validation.ok();
    }

    private boolean positionsValid(JsonNode items) {
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            if (!item.isObject() || item.path("position").asInt(-1) != i) {
                return false;
            }
        }
        return true;
    }

    private List<ItemJudgement> parseItems(JsonNode items, int expectedSize, String error) {
        List<ItemJudgement> out = new ArrayList<>();
        for (int i = 0; i < expectedSize; i++) {
            JsonNode item = items.get(i);
            out.add(new ItemJudgement(
                    item.path("entityMatch").asBoolean(true),
                    stringList(item.path("matchedEntities")),
                    stringList(item.path("outOfScopeEntities")),
                    item.path("relevanceWarning").asText(""),
                    true,
                    nvl(error)
            ));
        }
        return out;
    }

    private JudgeResult failOpen(String error, int hitCount, int citationCount) {
        String message = "搜索证据相关性 judge 未完成: " + nvl(error);
        return new JudgeResult(
                false,
                nvl(error),
                defaultItems(hitCount, message, error),
                defaultItems(citationCount, message, error)
        );
    }

    private List<ItemJudgement> defaultItems(int count, String warning, String error) {
        List<ItemJudgement> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new ItemJudgement(true, List.of(), List.of(), warning, false, nvl(error)));
        }
        return out;
    }

    private JsonNode parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            int firstLineEnd = trimmed.indexOf('\n', fenceStart);
            int contentStart = firstLineEnd < 0 ? fenceStart + 3 : firstLineEnd + 1;
            int fenceEnd = trimmed.indexOf("```", contentStart);
            if (fenceEnd > contentStart) {
                trimmed = trimmed.substring(contentStart, fenceEnd).trim();
                if (trimmed.regionMatches(true, 0, "json", 0, 4)) {
                    trimmed = trimmed.substring(4).trim();
                }
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        String json = (start >= 0 && end > start) ? trimmed.substring(start, end + 1) : trimmed;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.asText("").trim();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    private void restorePhaseAndStage(String previousPhase, String previousStage) {
        if (previousPhase == null || previousPhase.isBlank()) {
            AgentContext.clearPhase();
        } else {
            AgentContext.setPhase(previousPhase);
        }
        if (previousStage == null || previousStage.isBlank()) {
            AgentContext.clearStage();
        } else {
            AgentContext.setStage(previousStage);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String truncate(String value, int maxChars) {
        String text = nvl(value);
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    public record JudgeResult(
            boolean relevanceJudged,
            String relevanceJudgeError,
            List<ItemJudgement> hits,
            List<ItemJudgement> citations
    ) {
    }

    public record ItemJudgement(
            boolean entityMatch,
            List<String> matchedEntities,
            List<String> outOfScopeEntities,
            String relevanceWarning,
            boolean relevanceJudged,
            String relevanceJudgeError
    ) {
    }

    private record SelectionAndModel(JudgeModelSelectorService.Selection selection, ChatModel model) {
    }

    private record Validation(boolean valid, String error) {
        static Validation ok() {
            return new Validation(true, "");
        }

        static Validation invalid(String error) {
            return new Validation(false, error == null ? "" : error);
        }
    }
}

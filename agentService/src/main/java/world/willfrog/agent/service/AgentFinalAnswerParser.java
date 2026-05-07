package world.willfrog.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析最终回答，兼容 Markdown、裸 JSON 和 fenced JSON。
 */
@Service
@RequiredArgsConstructor
public class AgentFinalAnswerParser {

    private static final Pattern CITATION_REF = Pattern.compile("(?<![!\\w])\\[\\s*([1-9]\\d*)\\s*]");

    private final ObjectMapper objectMapper;

    public ParsedAnswer parse(String raw) {
        return parse(raw, AgentCitationService.CitationMap.empty());
    }

    public ParsedAnswer parse(String raw, AgentCitationService.CitationMap citationMap) {
        String normalizedRaw = raw == null ? "" : raw.trim();
        if (normalizedRaw.isBlank()) {
            return new ParsedAnswer("", "", null, List.of("EMPTY_ANSWER"));
        }

        String jsonCandidate = extractJsonCandidate(normalizedRaw);
        if (jsonCandidate != null) {
            try {
                JsonNode root = objectMapper.readTree(jsonCandidate);
                if (root != null && root.isObject()) {
                    Map<String, Object> structured = objectMapper.convertValue(
                            root, new TypeReference<Map<String, Object>>() {
                            });
                    String markdown = firstText(root, "answer_markdown", "markdown", "answer", "content");
                    if (markdown.isBlank()) {
                        markdown = normalizedRaw;
                    }
                    List<String> flags = readQualityFlags(root);
                    flags = withCitationFlags(markdown, citationMap, flags);
                    return new ParsedAnswer(normalizedRaw, markdown.trim(), structured, flags);
                }
            } catch (Exception ignored) {
                return new ParsedAnswer(normalizedRaw, normalizedRaw, null,
                        withCitationFlags(normalizedRaw, citationMap, List.of("JSON_PARSE_FALLBACK")));
            }
        }

        return new ParsedAnswer(normalizedRaw, normalizedRaw, null,
                withCitationFlags(normalizedRaw, citationMap, List.of()));
    }

    public String writeStructuredJson(Map<String, Object> structuredAnswer) {
        if (structuredAnswer == null || structuredAnswer.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(structuredAnswer);
        } catch (Exception e) {
            return "";
        }
    }

    private String extractJsonCandidate(String raw) {
        String fenced = extractFencedContent(raw);
        if (fenced != null) {
            String trimmed = fenced.trim();
            return trimmed.startsWith("{") && trimmed.endsWith("}") ? trimmed : null;
        }
        if (raw.startsWith("{") && raw.endsWith("}")) {
            return raw;
        }
        return null;
    }

    private String extractFencedContent(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) {
            return null;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        if (firstLineEnd < 0) {
            return null;
        }
        String fenceHeader = trimmed.substring(3, firstLineEnd).trim();
        if (!fenceHeader.isBlank() && !"json".equalsIgnoreCase(fenceHeader)) {
            return null;
        }
        return trimmed.substring(firstLineEnd + 1, trimmed.length() - 3).trim();
    }

    private String firstText(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode node = root.get(name);
            if (node != null && node.isTextual() && !node.asText("").isBlank()) {
                return node.asText("");
            }
        }
        return "";
    }

    private List<String> readQualityFlags(JsonNode root) {
        JsonNode node = root.get("quality_flags");
        if (node == null) {
            node = root.get("qualityFlags");
        }
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<String> flags = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String flag = item.asText("");
                if (!flag.isBlank()) {
                    flags.add(flag);
                }
            }
        } else if (node.isTextual() && !node.asText("").isBlank()) {
            flags.add(node.asText(""));
        }
        return flags;
    }

    private List<String> withCitationFlags(String markdown,
                                           AgentCitationService.CitationMap citationMap,
                                           List<String> existingFlags) {
        Set<String> flags = new LinkedHashSet<>();
        if (existingFlags != null) {
            flags.addAll(existingFlags);
        }
        Set<Integer> references = extractCitationReferences(markdown);
        if (references.isEmpty()) {
            return new ArrayList<>(flags);
        }
        if (citationMap == null || citationMap.isEmpty()) {
            flags.add("CITATION_REFERENCE_WITHOUT_MAP");
            return new ArrayList<>(flags);
        }
        for (Integer reference : references) {
            AgentCitationService.Citation citation = citationMap.byIndex(reference);
            if (citation == null) {
                flags.add("CITATION_REFERENCE_OUT_OF_RANGE");
                continue;
            }
            if (!citation.relevanceJudged()) {
                flags.add("CITATION_REFERENCE_JUDGE_FAIL_OPEN");
            }
            if (!citation.entityMatch() || !nvl(citation.relevanceWarning()).isBlank()) {
                flags.add("CITATION_REFERENCE_LOW_RELEVANCE");
            }
        }
        return new ArrayList<>(flags);
    }

    private Set<Integer> extractCitationReferences(String markdown) {
        Set<Integer> references = new LinkedHashSet<>();
        if (markdown == null || markdown.isBlank()) {
            return references;
        }
        Matcher matcher = CITATION_REF.matcher(markdown);
        while (matcher.find()) {
            try {
                references.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // 正则已限制为数字；此处仅防御极端溢出。
            }
        }
        return references;
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    public record ParsedAnswer(
            String answerRaw,
            String answerMarkdown,
            Map<String, Object> structuredAnswer,
            List<String> qualityFlags
    ) {
        public Map<String, Object> toSnapshotFields() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("answer_raw", answerRaw == null ? "" : answerRaw);
            fields.put("answer_markdown", answerMarkdown == null ? "" : answerMarkdown);
            fields.put("structured_answer", structuredAnswer == null ? Map.of() : structuredAnswer);
            fields.put("quality_flags", qualityFlags == null ? List.of() : qualityFlags);
            return fields;
        }
    }
}

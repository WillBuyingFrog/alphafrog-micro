package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentFinalAnswerParserTest {

    private final AgentFinalAnswerParser parser = new AgentFinalAnswerParser(new ObjectMapper());

    @Test
    void parse_shouldExtractAnswerFromFencedJson() {
        AgentFinalAnswerParser.ParsedAnswer parsed = parser.parse("""
                ```json
                {"answer":"最终答案","quality_flags":["HAS_CITATIONS"]}
                ```
                """);

        assertEquals("最终答案", parsed.answerMarkdown());
        assertNotNull(parsed.structuredAnswer());
        assertEquals("HAS_CITATIONS", parsed.qualityFlags().get(0));
    }

    @Test
    void parse_shouldKeepMarkdownAsDisplayAnswer() {
        AgentFinalAnswerParser.ParsedAnswer parsed = parser.parse("## 结论\n\n直接展示。");

        assertEquals("## 结论\n\n直接展示。", parsed.answerMarkdown());
        assertNull(parsed.structuredAnswer());
        assertTrue(parsed.qualityFlags().isEmpty());
    }

    @Test
    void parse_shouldFallbackWhenJsonInvalid() {
        AgentFinalAnswerParser.ParsedAnswer parsed = parser.parse("{\"answer\":}");

        assertEquals("{\"answer\":}", parsed.answerMarkdown());
        assertTrue(parsed.qualityFlags().contains("JSON_PARSE_FALLBACK"));
    }

    @Test
    void parse_shouldFlagCitationReferenceWithoutMap() {
        AgentFinalAnswerParser.ParsedAnswer parsed = parser.parse("结论来自来源 [1]。");

        assertTrue(parsed.qualityFlags().contains("CITATION_REFERENCE_WITHOUT_MAP"));
    }

    @Test
    void parse_shouldFlagOutOfRangeAndLowRelevanceCitation() {
        AgentCitationService.CitationMap citationMap = new AgentCitationService.CitationMap(List.of(
                new AgentCitationService.Citation(
                        1, 3, "低相关来源", "https://example.com/a", "todo_1",
                        false, false, "相关性判定失败")
        ));

        AgentFinalAnswerParser.ParsedAnswer parsed = parser.parse("结论 [1]，另见 [9]。", citationMap);

        assertTrue(parsed.qualityFlags().contains("CITATION_REFERENCE_JUDGE_FAIL_OPEN"));
        assertTrue(parsed.qualityFlags().contains("CITATION_REFERENCE_LOW_RELEVANCE"));
        assertTrue(parsed.qualityFlags().contains("CITATION_REFERENCE_OUT_OF_RANGE"));
    }

    @Test
    void parse_shouldRecognizeCitationReferenceWithSpaces() {
        AgentCitationService.CitationMap citationMap = new AgentCitationService.CitationMap(List.of(
                new AgentCitationService.Citation(
                        1, 1, "来源", "https://example.com/a", "todo_1",
                        true, true, "")
        ));

        AgentFinalAnswerParser.ParsedAnswer parsed = parser.parse("结论来自来源 [ 1 ]。", citationMap);

        assertTrue(parsed.qualityFlags().isEmpty());
    }

    @Test
    void parse_shouldNotTreatChineseBracketsAsCitationReference() {
        AgentFinalAnswerParser.ParsedAnswer parsed = parser.parse("结论来自来源【1】。");

        assertTrue(parsed.qualityFlags().isEmpty());
    }

    @Test
    void parse_shouldExtractFencedJsonWithNestedObject() {
        AgentFinalAnswerParser.ParsedAnswer parsed = parser.parse("""
                ```json
                {"answer_markdown":"结论 [1]","meta":{"reason":"包含 } 字符"}}
                ```
                """, new AgentCitationService.CitationMap(List.of(
                new AgentCitationService.Citation(
                        1, 1, "来源", "https://example.com", "todo_1",
                        true, true, "")
        )));

        assertEquals("结论 [1]", parsed.answerMarkdown());
        assertNotNull(parsed.structuredAnswer());
        assertTrue(parsed.qualityFlags().isEmpty());
    }
}

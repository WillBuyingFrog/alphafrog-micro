package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchEvidenceJudgeServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentAiServiceFactory aiServiceFactory = mock(AgentAiServiceFactory.class);
    private final JudgeModelSelectorService selectorService = mock(JudgeModelSelectorService.class);
    private final ChatModel model = mock(ChatModel.class);

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void judge_shouldUseLightweightJudgeRouteAndParseResult() {
        SearchEvidenceJudgeService service = newService();
        var selection = JudgeModelSelectorService.Selection.builder()
                .endpointName("cheap-judge")
                .endpointBaseUrl("https://example.com/v1")
                .modelName("small-model")
                .build();
        var resolved = new AgentLlmResolver.ResolvedLlm(
                "cheap-judge", "https://example.com/v1", "small-model", "key", "", List.of());
        when(selectorService.selectCandidates()).thenReturn(List.of(selection));
        when(aiServiceFactory.resolveLlm("cheap-judge", "small-model")).thenReturn(resolved);
        when(aiServiceFactory.buildChatModelWithProviderOrderAndTemperature(resolved, List.of(), 0.0D))
                .thenReturn(model);
        when(model.chat(any(List.class))).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("""
                        {"hits":[{"position":0,"entityMatch":false,"matchedEntities":["实体A"],"outOfScopeEntities":["实体C"],"relevanceWarning":"偏离问题"}],"citations":[{"position":0,"entityMatch":true,"matchedEntities":["实体A"],"outOfScopeEntities":[],"relevanceWarning":""}]}
                        """))
                .build());

        SearchEvidenceJudgeService.JudgeResult result = service.judge(
                "实体A还是实体B",
                List.of("实体A", "实体B"),
                List.of(hit("标题", "正文", "https://example.com/hit")),
                List.of(citation("来源", "https://example.com/source"))
        );

        assertTrue(result.relevanceJudged());
        assertEquals("", result.relevanceJudgeError());
        assertFalse(result.hits().get(0).entityMatch());
        assertEquals("实体C", result.hits().get(0).outOfScopeEntities().get(0));
        assertTrue(result.citations().get(0).entityMatch());
        verify(aiServiceFactory).buildChatModelWithProviderOrderAndTemperature(resolved, List.of(), 0.0D);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(model).chat(captor.capture());
        String prompt = captor.getValue().get(1).toString();
        assertTrue(prompt.contains("requested_entities"));
        assertTrue(prompt.contains("实体A"));
    }

    @Test
    void judge_shouldFailOpenWhenNoJudgeRoute() {
        SearchEvidenceJudgeService service = newService();
        when(selectorService.selectCandidates()).thenReturn(List.of());

        SearchEvidenceJudgeService.JudgeResult result = service.judge(
                "query", List.of("实体"), List.of(hit("t", "s", "u")), List.of());

        assertFalse(result.relevanceJudged());
        assertEquals("NO_JUDGE_ROUTE", result.relevanceJudgeError());
        assertTrue(result.hits().get(0).entityMatch());
        assertFalse(result.hits().get(0).relevanceJudged());
        assertTrue(result.hits().get(0).relevanceWarning().contains("judge 未完成"));
        verify(aiServiceFactory, never()).buildChatModelWithProviderOrderAndTemperature(any(), any(), any());
    }

    @Test
    void judge_shouldFailOpenWhenLlmThrows() {
        SearchEvidenceJudgeService service = serviceWithModel();
        when(model.chat(any(List.class))).thenThrow(new IllegalStateException("timeout"));

        SearchEvidenceJudgeService.JudgeResult result = service.judge(
                "query", List.of(), List.of(hit("t", "s", "u")), List.of());

        assertFalse(result.relevanceJudged());
        assertTrue(result.relevanceJudgeError().contains("JUDGE_CALL_FAILED"));
        assertTrue(result.hits().get(0).entityMatch());
        assertFalse(result.hits().get(0).relevanceJudged());
    }

    @Test
    void judge_shouldFailOpenWhenBadJson() {
        SearchEvidenceJudgeService service = serviceWithModel();
        when(model.chat(any(List.class))).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("不是 JSON"))
                .build());

        SearchEvidenceJudgeService.JudgeResult result = service.judge(
                "query", List.of(), List.of(hit("t", "s", "u")), List.of());

        assertFalse(result.relevanceJudged());
        assertEquals("JUDGE_BAD_JSON", result.relevanceJudgeError());
        assertTrue(result.hits().get(0).entityMatch());
    }

    @Test
    void judge_shouldParseFencedJsonWithoutNewline() {
        SearchEvidenceJudgeService service = serviceWithModel();
        when(model.chat(any(List.class))).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("""
                        ```json{"hits":[{"position":0,"entityMatch":true,"matchedEntities":["实体A"],"outOfScopeEntities":[],"relevanceWarning":""}],"citations":[]}```
                        """))
                .build());

        SearchEvidenceJudgeService.JudgeResult result = service.judge(
                "query", List.of("实体A"), List.of(hit("t", "s", "u")), List.of());

        assertTrue(result.relevanceJudged());
        assertEquals("", result.relevanceJudgeError());
        assertEquals("实体A", result.hits().get(0).matchedEntities().get(0));
    }

    @Test
    void judge_shouldFailOpenWhenCountMismatch() {
        SearchEvidenceJudgeService service = serviceWithModel();
        when(model.chat(any(List.class))).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("{\"hits\":[],\"citations\":[]}"))
                .build());

        SearchEvidenceJudgeService.JudgeResult result = service.judge(
                "query", List.of(), List.of(hit("t", "s", "u")), List.of());

        assertFalse(result.relevanceJudged());
        assertEquals("JUDGE_COUNT_MISMATCH", result.relevanceJudgeError());
        assertTrue(result.hits().get(0).entityMatch());
    }

    @Test
    void judge_shouldRestorePreviousContextAndTruncateInput() {
        SearchEvidenceJudgeService service = serviceWithModel();
        AgentContext.setPhase("old_phase");
        AgentContext.setStage("old_stage");
        when(model.chat(any(List.class))).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("{\"hits\":[{\"position\":0,\"entityMatch\":true}],\"citations\":[]}"))
                .build());

        String longSnippet = "x".repeat(1000);
        service.judge("query", List.of(), List.of(hit("t", longSnippet, "u")), List.of());

        assertEquals("old_phase", AgentContext.getPhase());
        assertEquals("old_stage", AgentContext.getStage());
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(model).chat(captor.capture());
        String userPayload = captor.getValue().get(1).toString();
        assertTrue(userPayload.contains("x".repeat(600)));
        assertFalse(userPayload.contains("x".repeat(601)));
    }

    private SearchEvidenceJudgeService serviceWithModel() {
        SearchEvidenceJudgeService service = newService();
        var selection = JudgeModelSelectorService.Selection.builder()
                .endpointName("cheap-judge")
                .modelName("small-model")
                .build();
        var resolved = new AgentLlmResolver.ResolvedLlm(
                "cheap-judge", "https://example.com/v1", "small-model", "key", "", List.of());
        when(selectorService.selectCandidates()).thenReturn(List.of(selection));
        when(aiServiceFactory.resolveLlm("cheap-judge", "small-model")).thenReturn(resolved);
        when(aiServiceFactory.buildChatModelWithProviderOrderAndTemperature(resolved, List.of(), 0.0D))
                .thenReturn(model);
        return service;
    }

    private SearchEvidenceJudgeService newService() {
        return new SearchEvidenceJudgeService(objectMapper, aiServiceFactory, selectorService);
    }

    private Map<String, Object> hit(String title, String snippet, String url) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", title);
        row.put("snippet", snippet);
        row.put("url", url);
        return row;
    }

    private Map<String, Object> citation(String title, String url) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", title);
        row.put("url", url);
        return row;
    }
}

package world.willfrog.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.service.SearchEvidenceJudgeService;
import world.willfrog.alphafrogmicro.externalinfo.idl.ExternalInfoDubboService;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchAnswerMeta;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchBackendMeta;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchCitation;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchHit;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRequest;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void searchWeb_shouldMarkOnlyQueryRequiredInToolSchema() {
        SearchTools tools = new SearchTools(objectMapper, mockJudge());

        ToolSpecification spec = ToolSpecifications.toolSpecificationsFrom(tools).stream()
                .filter(s -> "searchWeb".equals(s.name()))
                .findFirst()
                .orElseThrow();

        assertEquals(List.of("query"), spec.parameters().required());
    }

    @Test
    void searchWeb_shouldPassRunAndUserAndReturnProtocolFields() throws Exception {
        ExternalInfoDubboService dubboService = mock(ExternalInfoDubboService.class);
        when(dubboService.webSearch(any())).thenReturn(WebSearchResponse.newBuilder()
                .setOk(true)
                .setAnswer("answer")
                .setBackendMeta(WebSearchBackendMeta.newBuilder().setBackend("exa").setModelOrStrength("fast").build())
                .setAnswerMeta(WebSearchAnswerMeta.newBuilder().setAnswerType("backend_native").setModelUsed("exa").build())
                .setCanonicalQuery("canonical")
                .setSlotSignature("slot")
                .setResultHash("hash")
                .build());
        SearchTools tools = new SearchTools(objectMapper, mockJudge());
        ReflectionTestUtils.setField(tools, "externalInfoDubboService", dubboService);
        AgentContext.setRunId("run-1");
        AgentContext.setUserId("user-1");

        String json = tools.searchWeb("query", "", "", "", false, false, "", "", 0);

        var captor = org.mockito.ArgumentCaptor.forClass(WebSearchRequest.class);
        verify(dubboService).webSearch(captor.capture());
        assertEquals("run-1", captor.getValue().getRunId());
        assertEquals("user-1", captor.getValue().getUserId());

        JsonNode root = objectMapper.readTree(json);
        assertTrue(root.path("ok").asBoolean());
        assertEquals("canonical", root.path("data").path("canonical_query").asText());
        assertEquals("slot", root.path("data").path("slot_signature").asText());
        assertEquals("hash", root.path("data").path("result_hash").asText());
        assertEquals("backend_native", root.path("data").path("answer_meta").path("answer_type").asText());
        assertFalse(root.path("data").path("backend_meta").isMissingNode());
    }

    @Test
    void searchWeb_shouldApplyRunLevelWebSearchBackendConfig() {
        ExternalInfoDubboService dubboService = mock(ExternalInfoDubboService.class);
        when(dubboService.webSearch(any())).thenReturn(WebSearchResponse.newBuilder()
                .setOk(true)
                .setAnswer("answer")
                .build());
        SearchTools tools = new SearchTools(objectMapper, mockJudge());
        ReflectionTestUtils.setField(tools, "externalInfoDubboService", dubboService);
        AgentContext.setWebSearchConfig(new AgentContext.WebSearchConfig(
                "exa",
                "fast",
                true,
                true,
                6
        ));

        tools.searchWeb("query", "news", "perplexity", "standard", false, false, "", "", 10);

        var captor = org.mockito.ArgumentCaptor.forClass(WebSearchRequest.class);
        verify(dubboService).webSearch(captor.capture());
        WebSearchRequest request = captor.getValue();
        assertEquals("exa", request.getBackend());
        assertEquals("fast", request.getStrength());
        assertTrue(request.getSkipHotCache());
        assertTrue(request.getSkipRagPrefetch());
        assertEquals(6, request.getMaxResults());
    }

    @Test
    void searchWeb_shouldApplyJudgeFieldsToHitsAndCitations() throws Exception {
        ExternalInfoDubboService dubboService = mock(ExternalInfoDubboService.class);
        when(dubboService.webSearch(any())).thenReturn(WebSearchResponse.newBuilder()
                .setOk(true)
                .addHits(WebSearchHit.newBuilder()
                        .setTitle("专家讨论实体A")
                        .setSnippet("观点围绕实体A和实体B")
                        .setUrl("https://example.com/entity-a")
                        .build())
                .addCitations(WebSearchCitation.newBuilder()
                        .setIndex(1)
                        .setTitle("来源")
                        .setUrl("https://example.com/source")
                        .build())
                .build());
        SearchEvidenceJudgeService judgeService = mock(SearchEvidenceJudgeService.class);
        when(judgeService.judge(any(), any(), any(), any())).thenReturn(new SearchEvidenceJudgeService.JudgeResult(
                true,
                "",
                List.of(new SearchEvidenceJudgeService.ItemJudgement(
                        false, List.of("实体A"), List.of("实体C"), "包含非目标实体", true, "")),
                List.of(new SearchEvidenceJudgeService.ItemJudgement(
                        true, List.of("实体A"), List.of(), "", true, ""))
        ));
        SearchTools tools = new SearchTools(objectMapper, judgeService);
        ReflectionTestUtils.setField(tools, "externalInfoDubboService", dubboService);
        AgentContext.setExtractedEntities(List.of("实体A", "实体B"));

        String json = tools.searchWeb("实体A 实体B 哪个好", "finance", "", "", false, false, "", "", 5);

        verify(judgeService).judge(any(), any(), any(), any());
        JsonNode data = objectMapper.readTree(json).path("data");
        assertTrue(data.path("relevanceJudged").asBoolean());
        JsonNode hit = data.path("hits").get(0);
        assertFalse(hit.path("entityMatch").asBoolean());
        assertEquals("实体C", hit.path("outOfScopeEntities").get(0).asText());
        assertTrue(hit.path("relevanceWarning").asText().contains("非目标实体"));
        assertTrue(hit.path("relevanceJudged").asBoolean());
        JsonNode citation = data.path("citations").get(0);
        assertTrue(citation.path("entityMatch").asBoolean());
        assertTrue(citation.path("relevanceJudged").asBoolean());
    }

    @Test
    void searchWeb_shouldKeepResultsWhenJudgeFailsOpen() throws Exception {
        ExternalInfoDubboService dubboService = mock(ExternalInfoDubboService.class);
        when(dubboService.webSearch(any())).thenReturn(WebSearchResponse.newBuilder()
                .setOk(true)
                .addHits(WebSearchHit.newBuilder()
                        .setTitle("任意结果")
                        .setUrl("https://example.com")
                        .build())
                .build());
        SearchEvidenceJudgeService judgeService = mock(SearchEvidenceJudgeService.class);
        when(judgeService.judge(any(), any(), any(), any())).thenReturn(new SearchEvidenceJudgeService.JudgeResult(
                false,
                "JUDGE_BAD_JSON",
                List.of(new SearchEvidenceJudgeService.ItemJudgement(
                        true, List.of(), List.of(), "搜索证据相关性 judge 未完成: JUDGE_BAD_JSON", false, "JUDGE_BAD_JSON")),
                List.of()
        ));
        SearchTools tools = new SearchTools(objectMapper, judgeService);
        ReflectionTestUtils.setField(tools, "externalInfoDubboService", dubboService);

        String json = tools.searchWeb("query", "", "", "", false, false, "", "", 5);

        JsonNode data = objectMapper.readTree(json).path("data");
        assertFalse(data.path("relevanceJudged").asBoolean());
        assertEquals("JUDGE_BAD_JSON", data.path("relevanceJudgeError").asText());
        JsonNode hit = data.path("hits").get(0);
        assertEquals("任意结果", hit.path("title").asText());
        assertTrue(hit.path("entityMatch").asBoolean());
        assertFalse(hit.path("relevanceJudged").asBoolean());
        assertEquals("JUDGE_BAD_JSON", hit.path("relevanceJudgeError").asText());
    }

    private SearchEvidenceJudgeService mockJudge() {
        SearchEvidenceJudgeService judgeService = mock(SearchEvidenceJudgeService.class);
        when(judgeService.judge(any(), any(), any(), any())).thenReturn(new SearchEvidenceJudgeService.JudgeResult(
                true, "", List.of(), List.of()
        ));
        return judgeService;
    }
}

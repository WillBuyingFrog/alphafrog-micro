package world.willfrog.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.alphafrogmicro.externalinfo.idl.ExternalInfoDubboService;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchAnswerMeta;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchBackendMeta;
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
        SearchTools tools = new SearchTools(objectMapper);

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
        SearchTools tools = new SearchTools(objectMapper);
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
}

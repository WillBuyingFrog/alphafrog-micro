package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.tool.SearchTools;
import world.willfrog.alphafrogmicro.agent.idl.AgentToolMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentToolCatalogServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void listToolMessages_shouldExposeSearchWebFromToolAnnotations() throws Exception {
        AgentToolCatalogService service = new AgentToolCatalogService(
                null,
                null,
                new SearchTools(objectMapper, mock(SearchEvidenceJudgeService.class)),
                null,
                objectMapper
        );

        List<AgentToolMessage> tools = service.listToolMessages();

        AgentToolMessage searchWeb = tools.stream()
                .filter(tool -> "searchWeb".equals(tool.getName()))
                .findFirst()
                .orElseThrow();
        assertTrue(searchWeb.getDescription().contains("通用网络搜索工具"));
        JsonNode required = objectMapper.readTree(searchWeb.getParametersJson()).path("required");
        assertEquals(1, required.size());
        assertEquals("query", required.get(0).asText());
    }
}

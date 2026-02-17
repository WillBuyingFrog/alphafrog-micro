package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import world.willfrog.agent.mapper.AgentRunEventMapper;
import world.willfrog.agent.mapper.AgentRunMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class AgentEventServiceTest {

    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentRunEventMapper eventMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private AgentLlmLocalConfigLoader llmLocalConfigLoader;
    @Mock
    private AgentMessageService messageService;

    private AgentEventService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new AgentEventService(
                runMapper,
                eventMapper,
                objectMapper,
                redisTemplate,
                llmLocalConfigLoader,
                messageService
        );
    }

    @Test
    void extractRunConfig_shouldParseNestedContextConfig() throws Exception {
        String contextJson = objectMapper.writeValueAsString(Map.of(
                "config", Map.of(
                        "webSearch", Map.of("enabled", true),
                        "codeInterpreter", Map.of(
                                "enabled", false,
                                "maxCredits", 128
                        ),
                        "smartRetrieval", Map.of("enabled", true)
                )
        ));
        String extJson = objectMapper.writeValueAsString(Map.of("context_json", contextJson));

        AgentEventService.RunConfig config = service.extractRunConfig(extJson);

        assertTrue(config.webSearchEnabled());
        assertFalse(config.codeInterpreterEnabled());
        assertEquals(128, config.codeInterpreterMaxCredits());
        assertTrue(config.smartRetrievalEnabled());
    }

    @Test
    void extractRunConfig_shouldFallbackToCompatibleDefaultsWhenConfigMissing() throws Exception {
        String extJson = objectMapper.writeValueAsString(Map.of("context_json", "{}"));

        AgentEventService.RunConfig config = service.extractRunConfig(extJson);

        assertFalse(config.webSearchEnabled());
        assertTrue(config.codeInterpreterEnabled());
        assertEquals(0, config.codeInterpreterMaxCredits());
        assertFalse(config.smartRetrievalEnabled());
    }

    @Test
    void extractRunConfig_shouldFallbackToDefaultsWhenContextJsonMalformed() throws Exception {
        String extJson = objectMapper.writeValueAsString(Map.of("context_json", "{broken-json"));

        AgentEventService.RunConfig config = service.extractRunConfig(extJson);

        assertFalse(config.webSearchEnabled());
        assertTrue(config.codeInterpreterEnabled());
        assertEquals(0, config.codeInterpreterMaxCredits());
        assertFalse(config.smartRetrievalEnabled());
    }
}

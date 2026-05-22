package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.context.AgentContext;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunBudgetServiceTest {

    @Mock
    private AgentRunStateStore stateStore;
    @Mock
    private AgentEventService eventService;
    @Mock
    private AgentLlmLocalConfigLoader localConfigLoader;

    private AgentRunBudgetService service;

    @BeforeEach
    void setUp() {
        AgentLlmProperties llmProperties = new AgentLlmProperties();
        service = new AgentRunBudgetService(stateStore, eventService, new ObjectMapper(), llmProperties);
        ReflectionTestUtils.setField(service, "localConfigLoader", localConfigLoader);
        ReflectionTestUtils.setField(service, "defaultMaxWallClockMs", 600000L);
        ReflectionTestUtils.setField(service, "defaultMaxLlmCalls", 50L);
        ReflectionTestUtils.setField(service, "defaultMaxToolCalls", 30L);
        ReflectionTestUtils.setField(service, "defaultMaxTokens", 300000L);
        ReflectionTestUtils.setField(service, "defaultMaxHttpAttemptsPerLogicalCall", 2);
        AgentContext.setRunId("run-1");
        AgentContext.setUserId("user-1");
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void effectiveConfig_shouldUseApplicationDefaultsWhenNoDynamicConfig() {
        when(localConfigLoader.current()).thenReturn(Optional.empty());

        AgentRunBudgetService.EffectiveRunBudget budget = service.effectiveConfig();

        assertEquals(600000L, budget.maxWallClockMs());
        assertEquals(50L, budget.maxLlmCalls());
        assertEquals(30L, budget.maxToolCalls());
        assertEquals(300000L, budget.maxTokens());
        assertEquals(2, budget.maxHttpAttemptsPerLogicalCall());
    }

    @Test
    void effectiveConfig_shouldPreferLocalConfigOverApplicationDefaults() {
        AgentLlmProperties local = new AgentLlmProperties();
        AgentLlmProperties.RunBudget runBudget = new AgentLlmProperties.RunBudget();
        runBudget.setMaxToolCalls(80L);
        runBudget.setMaxLlmCalls(100L);
        local.getRuntime().setRunBudget(runBudget);
        when(localConfigLoader.current()).thenReturn(Optional.of(local));

        AgentRunBudgetService.EffectiveRunBudget budget = service.effectiveConfig();

        assertEquals(80L, budget.maxToolCalls());
        assertEquals(100L, budget.maxLlmCalls());
        assertEquals(300000L, budget.maxTokens());
    }

    @Test
    void checkBeforeToolCall_shouldUseUpdatedLocalBudgetWithoutRestart() {
        AgentLlmProperties local = new AgentLlmProperties();
        AgentLlmProperties.RunBudget runBudget = new AgentLlmProperties.RunBudget();
        runBudget.setMaxToolCalls(2L);
        local.getRuntime().setRunBudget(runBudget);
        when(localConfigLoader.current()).thenReturn(Optional.of(local));
        lenient().when(stateStore.loadObservability("run-1")).thenReturn(Optional.of("""
                {"summary":{"startedAtMillis":%d,"toolCalls":2,"llmCalls":0,"totalTokens":0}}
                """.formatted(System.currentTimeMillis())));

        IllegalStateException ex = assertThrows(IllegalStateException.class, service::checkBeforeToolCall);

        assertEquals("RUN_BUDGET_EXCEEDED:tool_calls:2/2", ex.getMessage());
    }
}

package world.willfrog.agent.service;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.tool.ToolRouter;
import world.willfrog.agent.workflow.WorkflowExecutionResult;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSimpleToolFastPathServiceTest {

    private ToolRouter toolRouter;
    private AgentSimpleToolFastPathService service;

    @BeforeEach
    void setUp() {
        toolRouter = mock(ToolRouter.class);
        service = new AgentSimpleToolFastPathService(toolRouter);
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    @Test
    void decide_shouldSelectSingleIndexSearch() {
        Optional<AgentSimpleToolFastPathService.FastPathDecision> decision = service.decide(
                "查一下沪深300指数",
                List.of(spec("searchIndex"))
        );

        assertTrue(decision.isPresent());
        assertTrue(decision.get().selected());
        assertEquals("searchIndex", decision.get().toolName());
        assertEquals("沪深300", decision.get().params().get("keyword"));
    }

    @Test
    void decide_shouldSkipComplexMultiStepQuestion() {
        Optional<AgentSimpleToolFastPathService.FastPathDecision> decision = service.decide(
                "去年每个月定投沪深300和中证500，然后今年专家观点怎么看",
                List.of(spec("searchIndex"))
        );

        assertTrue(decision.isPresent());
        assertFalse(decision.get().selected());
        assertEquals("multi_step_signal", decision.get().reason());
    }

    @Test
    void execute_shouldInvokeSelectedToolAndReturnMarkdownAnswer() {
        AgentSimpleToolFastPathService.FastPathDecision decision =
                AgentSimpleToolFastPathService.FastPathDecision.selected("searchIndex", java.util.Map.of("keyword", "沪深300"));
        when(toolRouter.invokeWithMeta(eq("searchIndex"), eq(java.util.Map.of("keyword", "沪深300"))))
                .thenReturn(ToolRouter.ToolInvocationResult.builder()
                        .output("{\"ok\":true}")
                        .success(true)
                        .durationMs(1L)
                        .build());

        WorkflowExecutionResult result = service.execute(decision);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getToolCallsUsed());
        assertTrue(result.getFinalAnswer().contains("searchIndex"));
        verify(toolRouter).invokeWithMeta(eq("searchIndex"), eq(java.util.Map.of("keyword", "沪深300")));
    }

    private ToolSpecification spec(String name) {
        return ToolSpecification.builder()
                .name(name)
                .description(name)
                .build();
    }
}

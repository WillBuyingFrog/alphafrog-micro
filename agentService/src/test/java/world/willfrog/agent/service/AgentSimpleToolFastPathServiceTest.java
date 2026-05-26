package world.willfrog.agent.service;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.tools.router.ToolRouter;
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
    void decide_shouldSelectSearchAssetInfoForEtfQuery() {
        Optional<AgentSimpleToolFastPathService.FastPathDecision> decision = service.decide(
                "查一下半导体行业主题ETF",
                List.of(spec("searchFund"), spec("searchAssetInfo"))
        );

        assertTrue(decision.isPresent());
        assertTrue(decision.get().selected());
        assertEquals("searchAssetInfo", decision.get().toolName());
        assertEquals("etf", decision.get().params().get("assetTypes"));
    }

    @Test
    void decide_shouldSelectSearchFundForOffExchangeFundQuery() {
        Optional<AgentSimpleToolFastPathService.FastPathDecision> decision = service.decide(
                "查一下易方达蓝筹精选混合型基金",
                List.of(spec("searchFund"), spec("searchAssetInfo"))
        );

        assertTrue(decision.isPresent());
        assertTrue(decision.get().selected());
        assertEquals("searchFund", decision.get().toolName());
    }

    @Test
    void decide_shouldPreferSearchAssetInfoWhenTextContainsListedMarketSignal() {
        Optional<AgentSimpleToolFastPathService.FastPathDecision> decision = service.decide(
                "查一下消费行业主题场内基金",
                List.of(spec("searchFund"), spec("searchAssetInfo"))
        );

        assertTrue(decision.isPresent());
        assertTrue(decision.get().selected());
        assertEquals("searchAssetInfo", decision.get().toolName());
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

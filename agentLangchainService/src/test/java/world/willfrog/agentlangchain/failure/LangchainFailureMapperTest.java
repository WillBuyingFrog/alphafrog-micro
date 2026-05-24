package world.willfrog.agentlangchain.failure;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.model.AgentRunStatus;

import static org.junit.jupiter.api.Assertions.*;

class LangchainFailureMapperTest {

    private final LangchainFailureMapper mapper = new LangchainFailureMapper();

    @Test
    void map_shouldMapBudgetExceeded() {
        LangchainFailureDecision decision = mapper.map(
                "tool_execution",
                "todo_1",
                "executePython",
                "RUN_BUDGET_EXCEEDED:wall_clock_ms:651976/600000",
                null,
                null,
                12);

        assertEquals(AgentRunStatus.FAILED, decision.getRunStatus());
        assertEquals("RUN_BUDGET_EXCEEDED", decision.getEventType());
        assertEquals(LangchainFailureCategory.BUDGET_EXCEEDED, decision.getCategory());
        assertFalse(decision.isRetryable());
        assertEquals("RunBudgetExceeded", decision.getObservabilityFailureType());
        assertEquals("todo_1", decision.getEventPayload().get("todo_id"));
        assertEquals(12, decision.getEventPayload().get("tool_calls_used"));
    }

    @Test
    void map_shouldMapRepeatedToolCallAsRetryableToolError() {
        LangchainFailureDecision decision = mapper.map(
                "tool_execution",
                "todo_2",
                "searchWeb",
                null,
                "{\"success\":false,\"code\":\"REPEATED_TOOL_CALL\",\"message\":\"repeated_tool_call\"}",
                null,
                3);

        assertEquals("TOOL_ERROR", decision.getEventType());
        assertEquals(LangchainFailureCategory.REPEATED_TOOL_CALL, decision.getCategory());
        assertTrue(decision.isRetryable());
        assertEquals("RepeatedToolCall", decision.getObservabilityFailureType());
        assertEquals("REPEATED_TOOL_CALL", decision.getEventPayload().get("error_code"));
    }

    @Test
    void map_shouldMapDatasetParameterErrors() {
        LangchainFailureDecision decision = mapper.map(
                "tool_execution",
                "todo_3",
                "executePython",
                null,
                "{\"ok\":false,\"error\":{\"code\":\"TASK_FAILED\",\"message\":\"dataset_id directory not found\"}}",
                null,
                2);

        assertEquals("TOOL_ERROR", decision.getEventType());
        assertEquals(LangchainFailureCategory.PARAM_RETRY_WITH_HINT, decision.getCategory());
        assertTrue(decision.isRetryable());
        assertEquals("ParameterRetryWithHint", decision.getObservabilityFailureType());
    }

    @Test
    void map_shouldMapBlankFinalAnswerAsWorkflowFailure() {
        LangchainFailureDecision decision = mapper.map("empty_final_answer");

        assertEquals("WORKFLOW_FAILED", decision.getEventType());
        assertEquals(LangchainFailureCategory.EMPTY_OUTPUT, decision.getCategory());
        assertFalse(decision.isRetryable());
    }

    @Test
    void map_shouldMapUnknownToWorkflowFailed() {
        LangchainFailureDecision decision = mapper.map(
                "summarizing",
                null,
                null,
                "unexpected failure",
                null,
                new IllegalStateException("boom"),
                null);

        assertEquals("WORKFLOW_FAILED", decision.getEventType());
        assertEquals(LangchainFailureCategory.UNKNOWN, decision.getCategory());
        assertFalse(decision.isRetryable());
        assertTrue(decision.getReason().contains("unexpected failure"));
        assertTrue(decision.getReason().contains("boom"));
    }
}

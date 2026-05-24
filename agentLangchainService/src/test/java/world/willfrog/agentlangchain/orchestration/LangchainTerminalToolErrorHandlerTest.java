package world.willfrog.agentlangchain.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LangchainTerminalToolErrorHandlerTest {

    @Test
    void handle_shouldRethrowBudgetExceededSignal() {
        IllegalStateException error = new IllegalStateException("RUN_BUDGET_EXCEEDED:tool_calls:30/30");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> LangchainTerminalToolErrorHandler.handle(error, null));

        assertEquals("RUN_BUDGET_EXCEEDED:tool_calls:30/30", thrown.getMessage());
    }

    @Test
    void handle_shouldRethrowInterruptedSignalFromCause() {
        RuntimeException error = new RuntimeException(
                "wrapped",
                new IllegalStateException("RUN_INTERRUPTED:CANCELING"));

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> LangchainTerminalToolErrorHandler.handle(error, null));

        assertEquals("wrapped", thrown.getMessage());
    }

    @Test
    void handle_shouldReturnRecoverableToolErrorText() {
        var result = LangchainTerminalToolErrorHandler.handle(
                new IllegalArgumentException("invalid parameter"),
                null);

        assertEquals("invalid parameter", result.text());
    }
}

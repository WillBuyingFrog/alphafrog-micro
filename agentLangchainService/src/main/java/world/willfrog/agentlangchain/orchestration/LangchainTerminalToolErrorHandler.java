package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;

/**
 * Converts recoverable tool execution exceptions into ordinary tool error text,
 * but lets terminal run-control signals escape the LangChain4j tool loop.
 */
final class LangchainTerminalToolErrorHandler {

    private LangchainTerminalToolErrorHandler() {
    }

    static ToolErrorHandlerResult handle(Throwable throwable, ToolErrorContext ignored) {
        if (isTerminalSignal(throwable)) {
            throw asRuntimeException(throwable);
        }
        return ToolErrorHandlerResult.text(firstNonBlankMessage(throwable));
    }

    static boolean isTerminalSignal(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && (message.startsWith("RUN_BUDGET_EXCEEDED:")
                    || message.startsWith("RUN_INTERRUPTED:"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static RuntimeException asRuntimeException(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException(firstNonBlankMessage(throwable), throwable);
    }

    private static String firstNonBlankMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return throwable == null ? "tool_execution_failed" : throwable.getClass().getSimpleName();
    }
}

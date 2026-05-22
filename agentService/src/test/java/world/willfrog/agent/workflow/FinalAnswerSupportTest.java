package world.willfrog.agent.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FinalAnswerSupportTest {

    @Test
    void resolveAnswerOrEmpty_shouldPreferLlmText() {
        String resolved = FinalAnswerSupport.resolveAnswerOrEmpty("final");
        assertEquals("final", resolved);
    }

    @Test
    void resolveAnswerOrEmpty_shouldStayEmptyWithoutFallback() {
        String resolved = FinalAnswerSupport.resolveAnswerOrEmpty("");
        assertEquals("", resolved);
    }

    @Test
    void lastNonBlankTodoOutput_shouldReturnLatestOutput() {
        String resolved = FinalAnswerSupport.lastNonBlankTodoOutput(List.of(
                CompletedTodoInfo.builder().todoId("todo_1").output("first").build(),
                CompletedTodoInfo.builder().todoId("todo_2").output("last todo output").build()
        ));
        assertEquals("last todo output", resolved);
    }
}

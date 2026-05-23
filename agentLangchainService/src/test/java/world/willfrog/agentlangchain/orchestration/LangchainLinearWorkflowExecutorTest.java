package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LangchainLinearWorkflowExecutorTest {

    @Test
    void execute_shouldRunPlanTodosAndFinalAnswerInOrder() {
        QueueChatModel model = new QueueChatModel(
                """
                {
                  "analysis": "linear",
                  "items": [
                    {"id":"todo_1","sequence":1,"description":"查询沪深300"},
                    {"id":"todo_2","sequence":2,"description":"总结走势","dependsOn":["todo_1"]}
                  ],
                  "extractedEntities": ["沪深300"]
                }
                """,
                "todo1 output",
                "todo2 output based on todo1",
                "final answer"
        );
        LangchainLinearWorkflowExecutor executor = new LangchainLinearWorkflowExecutor(
                new LangchainAiPlanner(),
                Optional.empty()
        );

        LangchainLinearWorkflowResult result = executor.execute(LangchainLinearWorkflowRequest.builder()
                .runId("run-linear-1")
                .userId("user-1")
                .userGoal("分析沪深300")
                .model(model)
                .maxTodos(5)
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("final answer");
        assertThat(result.getCompletedTodos()).hasSize(2);
        assertThat(result.getCompletedTodos().get(0).getOutput()).isEqualTo("todo1 output");
        assertThat(result.getCompletedTodos().get(1).getOutput()).contains("todo1");
        assertThat(result.getPlan().getExtractedEntities()).containsExactly("沪深300");
        assertThat(model.requests()).hasSize(4);
        assertThat(model.requests().get(2).toString()).contains("todo1 output");
        assertThat(model.requests().get(3).toString()).contains("todo2 output based on todo1");
        assertThat(AgentContext.getRunId()).isNull();
    }

    @Test
    void execute_shouldFailWhenTodoOutputIsBlank() {
        QueueChatModel model = new QueueChatModel(
                """
                {"analysis":"linear","items":[{"id":"todo_1","sequence":1,"description":"查询"}],"extractedEntities":[]}
                """,
                "   "
        );
        LangchainLinearWorkflowExecutor executor = new LangchainLinearWorkflowExecutor(
                new LangchainAiPlanner(),
                Optional.empty()
        );

        LangchainLinearWorkflowResult result = executor.execute(LangchainLinearWorkflowRequest.builder()
                .userGoal("分析")
                .model(model)
                .build());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo("empty_todo_output:todo_1");
        assertThat(result.getFinalAnswer()).isNull();
    }

    static class QueueChatModel implements ChatModel {
        private final List<String> responses;
        private final List<ChatRequest> requests = new ArrayList<>();
        private int index;

        QueueChatModel(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            String response = index < responses.size() ? responses.get(index++) : "";
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();
        }

        List<ChatRequest> requests() {
            return requests;
        }
    }
}

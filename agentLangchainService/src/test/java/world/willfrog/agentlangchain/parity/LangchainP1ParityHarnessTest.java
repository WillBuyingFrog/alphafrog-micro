package world.willfrog.agentlangchain.parity;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import world.willfrog.agentlangchain.support.LangchainTestFixtures;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainPlanningRequest;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1 batch-1 seed: langchain planner path with mock ChatModel (mirrors legacy parity intent).
 */
class LangchainP1ParityHarnessTest {

    private final LangchainAiPlanner planner = LangchainTestFixtures.planner();

    @Test
    void linear_simple_success_plan() {
        ChatModel model = new JsonChatModel("""
                {
                  "analysis": "simple task",
                  "items": [
                    {"id": "t1", "sequence": 1, "description": "todo 1"}
                  ]
                }
                """);

        LangchainTodoPlan plan = planner.plan(LangchainPlanningRequest.builder()
                .userGoal("analyze one stock")
                .model(model)
                .executionMode(PlanExecutionMode.LINEAR)
                .build());

        assertThat(plan.getItems()).hasSize(1);
        assertThat(plan.getItems().get(0).getId()).isEqualTo("t1");
        assertThat(plan.getExecutionMode()).isEqualTo(PlanExecutionMode.LINEAR);
    }

    @Test
    void empty_plan_fails_like_legacy_parity() {
        ChatModel model = new JsonChatModel("""
                {"analysis": "none", "items": []}
                """);

        assertThatThrownBy(() -> planner.plan(LangchainPlanningRequest.builder()
                .userGoal("do nothing")
                .model(model)
                .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("todo_plan_empty");
    }

    private static final class JsonChatModel implements ChatModel {
        private final String response;

        private JsonChatModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from(response)).build();
        }
    }
}

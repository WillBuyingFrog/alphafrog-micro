package world.willfrog.agentlangchain.planning;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangchainAiPlannerTest {

    private final LangchainAiPlanner planner = new LangchainAiPlanner();

    @Test
    void plan_shouldUseAiServiceStructuredOutputAndNormalizeTodoPlan() {
        RecordingChatModel model = new RecordingChatModel("""
                {
                  "analysis": "先查数据再总结。",
                  "items": [
                    {
                      "id": "",
                      "sequence": 0,
                      "description": "查询沪深300近一年走势。",
                      "dependsOn": ["", "todo_0"],
                      "parallelizable": true
                    },
                    {
                      "id": "todo_2",
                      "sequence": 2,
                      "description": "基于查询结果生成结论。"
                    }
                  ],
                  "extractedEntities": ["沪深300", "沪深300", "2025"]
                }
                """);

        LangchainTodoPlan plan = planner.plan(LangchainPlanningRequest.builder()
                .runId("run-1")
                .userId("user-1")
                .userGoal("分析沪深300近一年走势")
                .dialogueContext("无")
                .model(model)
                .toolSpecifications(ToolSpecifications.toolSpecificationsFrom(new DemoTools()))
                .executionMode(PlanExecutionMode.LINEAR)
                .maxTodos(5)
                .build());

        assertThat(plan.getExecutionMode()).isEqualTo(PlanExecutionMode.LINEAR);
        assertThat(plan.getAnalysis()).contains("查数据");
        assertThat(plan.getExtractedEntities()).containsExactly("沪深300", "2025");
        assertThat(plan.getItems()).hasSize(2);
        assertThat(plan.getItems().get(0).getId()).isEqualTo("todo_1");
        assertThat(plan.getItems().get(0).getSequence()).isEqualTo(1);
        assertThat(plan.getItems().get(0).getStatus()).isEqualTo(TodoStatus.PENDING);
        assertThat(plan.getItems().get(0).getDependsOn()).containsExactly("todo_0");
        assertThat(plan.getItems().get(0).isParallelizable()).isTrue();
        assertThat(model.lastRequest.toString()).contains("searchIndex");
        assertThat(model.lastRequest.toString()).contains("Maximum todo count: 5");
    }

    @Test
    void plan_shouldRejectMissingModel() {
        assertThatThrownBy(() -> planner.plan(LangchainPlanningRequest.builder()
                .userGoal("hello")
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planning_chat_model_required");
    }

    @Test
    void plan_shouldFailOnEmptyItems() {
        RecordingChatModel model = new RecordingChatModel("""
                {"analysis":"empty","items":[],"extractedEntities":[]}
                """);

        assertThatThrownBy(() -> planner.plan(LangchainPlanningRequest.builder()
                .userGoal("hello")
                .model(model)
                .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("todo_plan_empty");
    }

    static class RecordingChatModel implements ChatModel {
        private final String response;
        private ChatRequest lastRequest;

        RecordingChatModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            this.lastRequest = request;
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();
        }
    }

    static class DemoTools {
        @Tool("Search index data")
        String searchIndex(String query) {
            return query;
        }
    }
}

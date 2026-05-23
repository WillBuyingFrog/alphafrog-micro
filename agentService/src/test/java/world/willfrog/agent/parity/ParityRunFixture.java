package world.willfrog.agent.parity;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import org.mockito.Mockito;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agent.workflow.TodoPlan;
import world.willfrog.agent.workflow.WorkflowRequest;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 为 parity harness 构造 mock 运行环境。
 *
 * <p>提供标准 fixture：mock ChatModel、mock 工具结果、mock Run 状态、
 * TodoPlan 构造 helper。所有 mock 使用 lenient() 避免未使用 stub 报错。</p>
 */
public class ParityRunFixture {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatModel mockChatModel(String responseText) {
        ChatModel model = mock(ChatModel.class);
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.builder().text(responseText).build())
                .metadata(ChatResponseMetadata.builder().build())
                .build();
        lenient().when(model.chat(anyList())).thenReturn(response);
        return model;
    }

    public AgentEventService mockEventService() {
        return mock(AgentEventService.class);
    }

    public AgentPromptService mockPromptService() {
        AgentPromptService service = mock(AgentPromptService.class);
        lenient().when(service.dynamicContextPrefix()).thenReturn("今天是2026年05月23日。");
        lenient().when(service.dagReactSystemPrompt()).thenReturn("system prompt");
        lenient().when(service.finalAnswerStageInstruction()).thenReturn("[Stage: FINAL_ANSWER]\n");
        return service;
    }

    public AgentRunStateStore mockStateStore() {
        AgentRunStateStore store = mock(AgentRunStateStore.class);
        lenient().when(store.loadRunStatus(anyString())).thenReturn(Optional.empty());
        return store;
    }

    public AgentRun mockAgentRun(String runId, String goal) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setStatus(AgentRunStatus.RECEIVED);
        run.setStartedAt(OffsetDateTime.now());
        return run;
    }

    public TodoPlan simpleLinearPlan(int todoCount) {
        List<TodoItem> items = new ArrayList<>();
        for (int i = 0; i < todoCount; i++) {
            items.add(TodoItem.builder()
                    .id("t" + (i + 1))
                    .sequence(i)
                    .description("todo " + (i + 1))
                    .build());
        }
        return TodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(items)
                .build();
    }

    public TodoPlan simpleLinearPlanWithDatasetHandoff() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("t1")
                .sequence(0)
                .description("fetch data")
                .build());
        items.add(TodoItem.builder()
                .id("t2")
                .sequence(1)
                .description("analyze data")
                .dependsOn(List.of("t1"))
                .build());
        return TodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(items)
                .build();
    }

    public WorkflowRequest workflowRequest(String runId, String goal, TodoPlan plan, ChatModel model) {
        return WorkflowRequest.builder()
                .run(mockAgentRun(runId, goal))
                .userId("u1")
                .userGoal(goal)
                .plan(plan)
                .model(model)
                .finalAnswerModel(model)
                .toolSpecifications(List.of())
                .endpointName("test")
                .modelName("test-model")
                .enablePlanPatch(false)
                .build();
    }

    public String mockToolResponse(String toolName, boolean ok, Object data) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "ok", ok,
                    "data", data != null ? data : Map.of()
            ));
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }
}

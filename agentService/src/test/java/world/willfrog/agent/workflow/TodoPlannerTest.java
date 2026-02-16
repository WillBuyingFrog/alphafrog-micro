package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.service.AgentLlmRequestSnapshotBuilder;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentRunStateStore;
import world.willfrog.agent.service.AgentMessageService;
import world.willfrog.agent.service.AgentContextCompressor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoPlannerTest {

    @Mock
    private AgentPromptService promptService;
    @Mock
    private AgentEventService eventService;
    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentRunStateStore stateStore;
    @Mock
    private AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    @Mock
    private AgentObservabilityService observabilityService;
    @Mock
    private AgentLlmLocalConfigLoader localConfigLoader;
    @Mock
    private ChatLanguageModel model;
    @Mock
    private AgentMessageService messageService;
    @Mock
    private AgentContextCompressor contextCompressor;

    private TodoPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new TodoPlanner(
                promptService,
                eventService,
                runMapper,
                stateStore,
                llmRequestSnapshotBuilder,
                observabilityService,
                localConfigLoader,
                new AgentLlmProperties(),
                messageService,
                contextCompressor,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(planner, "defaultMaxTodos", 2);

        lenient().when(localConfigLoader.current()).thenReturn(Optional.empty());
        lenient().when(llmRequestSnapshotBuilder.buildChatCompletionsRequest(anyString(), anyString(), anyString(), any(), any(), anyMap()))
                .thenReturn(Map.of());
        lenient().when(promptService.todoPlannerSystemPrompt(anyString(), any(Integer.class))).thenReturn("todo-prompt");
        lenient().when(stateStore.isPlanOverride(anyString())).thenReturn(false);
        lenient().when(stateStore.loadPlan(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void plan_shouldGenerateTodoListAndPersist() {
        AgentRun run = run("run-1");

        @SuppressWarnings("unchecked")
        Response<AiMessage> response = mock(Response.class);
        AiMessage aiMessage = mock(AiMessage.class);
        when(aiMessage.text()).thenReturn("{\"analysis\":\"a\",\"items\":[{\"id\":\"todo_1\",\"type\":\"TOOL_CALL\",\"toolName\":\"searchStock\",\"params\":{\"keyword\":\"平安\"}}]}");
        when(response.content()).thenReturn(aiMessage);
        when(response.tokenUsage()).thenReturn(null);
        when(model.generate(any(List.class))).thenReturn(response);

        TodoPlan plan = planner.plan(TodoPlanner.PlanRequest.builder()
                .run(run)
                .userId("u1")
                .userGoal("goal")
                .model(model)
                .toolSpecifications(List.of(ToolSpecification.builder().name("searchStock").description("d").build()))
                .endpointName("ep")
                .endpointBaseUrl("base")
                .modelName("m")
                .build());

        assertEquals(1, plan.getItems().size());
        assertEquals("searchStock", plan.getItems().get(0).getToolName());
        verify(runMapper).updatePlan(eq("run-1"), eq("u1"), eq(AgentRunStatus.EXECUTING), anyString());
        verify(eventService).append(eq("run-1"), eq("u1"), eq("TODO_LIST_CREATED"), anyMap());
    }

    @Test
    void plan_shouldTruncateByMaxTodos() {
        AgentRun run = run("run-2");

        @SuppressWarnings("unchecked")
        Response<AiMessage> response = mock(Response.class);
        AiMessage aiMessage = mock(AiMessage.class);
        when(aiMessage.text()).thenReturn("{\"items\":[{\"id\":\"1\",\"type\":\"TOOL_CALL\",\"toolName\":\"searchStock\"},{\"id\":\"2\",\"type\":\"TOOL_CALL\",\"toolName\":\"searchStock\"},{\"id\":\"3\",\"type\":\"TOOL_CALL\",\"toolName\":\"searchStock\"}]}");
        when(response.content()).thenReturn(aiMessage);
        when(response.tokenUsage()).thenReturn(null);
        when(model.generate(any(List.class))).thenReturn(response);

        TodoPlan plan = planner.plan(TodoPlanner.PlanRequest.builder()
                .run(run)
                .userId("u1")
                .userGoal("goal")
                .model(model)
                .toolSpecifications(List.of(ToolSpecification.builder().name("searchStock").description("d").build()))
                .endpointName("ep")
                .endpointBaseUrl("base")
                .modelName("m")
                .build());

        assertEquals(2, plan.getItems().size());
    }

    @Test
    void plan_shouldFailWhenToolNotAllowed() {
        AgentRun run = run("run-3");

        @SuppressWarnings("unchecked")
        Response<AiMessage> response = mock(Response.class);
        AiMessage aiMessage = mock(AiMessage.class);
        when(aiMessage.text()).thenReturn("{\"items\":[{\"id\":\"todo_1\",\"type\":\"TOOL_CALL\",\"toolName\":\"unknownTool\"}]}");
        when(response.content()).thenReturn(aiMessage);
        when(response.tokenUsage()).thenReturn(null);
        when(model.generate(any(List.class))).thenReturn(response);

        assertThrows(IllegalStateException.class, () -> planner.plan(TodoPlanner.PlanRequest.builder()
                .run(run)
                .userId("u1")
                .userGoal("goal")
                .model(model)
                .toolSpecifications(List.of(ToolSpecification.builder().name("searchStock").description("d").build()))
                .endpointName("ep")
                .endpointBaseUrl("base")
                .modelName("m")
                .build()));

        verify(eventService).append(eq("run-3"), eq("u1"), eq("PLANNING_FAILED"), anyMap());
    }

    private AgentRun run(String id) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setUserId("u1");
        run.setExt("{}");
        return run;
    }
}

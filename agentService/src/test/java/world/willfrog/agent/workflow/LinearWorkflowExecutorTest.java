package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.graph.SubAgentRunner;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentCreditService;
import world.willfrog.agent.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.service.AgentLlmRequestSnapshotBuilder;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentRunStateStore;
import world.willfrog.agent.tool.ToolRouter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinearWorkflowExecutorTest {

    @Mock
    private AgentEventService eventService;
    @Mock
    private AgentPromptService promptService;
    @Mock
    private ToolRouter toolRouter;
    @Mock
    private SubAgentRunner subAgentRunner;
    @Mock
    private AgentRunStateStore stateStore;
    @Mock
    private AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    @Mock
    private AgentObservabilityService observabilityService;
    @Mock
    private AgentCreditService creditService;
    @Mock
    private AgentLlmLocalConfigLoader localConfigLoader;
    @Mock
    private ChatLanguageModel model;

    private LinearWorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        ToolCallCounter counter = new ToolCallCounter(stateStore);
        TodoParamResolver resolver = new TodoParamResolver();
        executor = new LinearWorkflowExecutor(
                eventService,
                promptService,
                toolRouter,
                subAgentRunner,
                resolver,
                counter,
                stateStore,
                llmRequestSnapshotBuilder,
                observabilityService,
                creditService,
                localConfigLoader,
                new AgentLlmProperties(),
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(executor, "defaultMaxToolCalls", 20);
        ReflectionTestUtils.setField(executor, "defaultMaxToolCallsPerSubAgent", 10);
        ReflectionTestUtils.setField(executor, "defaultMaxRetriesPerTodo", 3);
        ReflectionTestUtils.setField(executor, "defaultFailFast", false);
        ReflectionTestUtils.setField(executor, "defaultExecutionMode", "AUTO");
        ReflectionTestUtils.setField(executor, "defaultSubAgentEnabled", true);
        ReflectionTestUtils.setField(executor, "defaultSubAgentMaxSteps", 6);

        lenient().when(stateStore.loadWorkflowState(anyString())).thenReturn(Optional.empty());
        lenient().when(stateStore.getToolCallCount(anyString())).thenReturn(0);
        lenient().when(stateStore.incrementToolCallCount(anyString(), anyInt())).thenReturn(1);
        lenient().when(promptService.workflowFinalSystemPrompt()).thenReturn("final");
        lenient().when(promptService.workflowTodoRecoverySystemPrompt()).thenReturn("recovery");
        lenient().when(localConfigLoader.current()).thenReturn(Optional.empty());
        lenient().when(creditService.calculateToolCredits(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(1);
        lenient().when(llmRequestSnapshotBuilder.buildChatCompletionsRequest(anyString(), anyString(), anyString(), any(), any(), anyMap()))
                .thenReturn(Map.of());

        @SuppressWarnings("unchecked")
        Response<AiMessage> response = mock(Response.class);
        AiMessage aiMessage = mock(AiMessage.class);
        lenient().when(aiMessage.text()).thenReturn("done");
        lenient().when(response.content()).thenReturn(aiMessage);
        lenient().when(response.tokenUsage()).thenReturn(null);
        lenient().when(model.generate(any(List.class))).thenReturn(response);
    }

    @Test
    void execute_shouldCompleteWhenToolCallSucceeds() {
        when(eventService.isRunnable("run-1", "u1")).thenReturn(true);
        when(toolRouter.invokeWithMeta(eq("searchStock"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder().success(true).output("{\"ok\":true}").build()
        );

        WorkflowExecutionResult result = executor.execute(request("run-1", planWithTools(1), new AgentLlmProperties()));

        assertTrue(result.isSuccess());
        assertFalse(result.isPaused());
        verify(eventService).append(eq("run-1"), eq("u1"), eq("TODO_FINISHED"), anyMap());
    }

    @Test
    void execute_shouldPauseWhenRunNotRunnable() {
        when(eventService.isRunnable("run-2", "u1")).thenReturn(false);

        WorkflowExecutionResult result = executor.execute(request("run-2", planWithTools(1), new AgentLlmProperties()));

        assertTrue(result.isPaused());
        verify(stateStore).saveWorkflowState(eq("run-2"), any());
        verify(eventService).append(eq("run-2"), eq("u1"), eq("WORKFLOW_PAUSED"), anyMap());
    }

    @Test
    void execute_shouldEmitToolCallPayloadWithCreditsAndDisplayFields() {
        when(eventService.isRunnable("run-4", "u1")).thenReturn(true);
        when(creditService.calculateToolCredits(eq("searchStock"), eq(false))).thenReturn(1);
        when(toolRouter.invokeWithMeta(eq("searchStock"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder().success(true).output("{\"ok\":true}").build()
        );

        executor.execute(request("run-4", planWithTools(1), new AgentLlmProperties()));

        ArgumentCaptor<Map<String, Object>> startedCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-4"), eq("u1"), eq("TOOL_CALL_STARTED"), startedCaptor.capture());
        Map<String, Object> startedPayload = startedCaptor.getValue();
        assertTrue(startedPayload.containsKey("toolName"));
        assertTrue(startedPayload.containsKey("displayName"));
        assertTrue(startedPayload.containsKey("description"));

        ArgumentCaptor<Map<String, Object>> finishedCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-4"), eq("u1"), eq("TOOL_CALL_FINISHED"), finishedCaptor.capture());
        Map<String, Object> finishedPayload = finishedCaptor.getValue();
        assertTrue(finishedPayload.containsKey("cacheHit"));
        assertTrue(finishedPayload.containsKey("creditsConsumed"));
    }

    @Test
    void execute_shouldFailFastWhenToolCallLimitReached() {
        when(eventService.isRunnable("run-3", "u1")).thenReturn(true, true);
        when(toolRouter.invokeWithMeta(eq("searchStock"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder().success(true).output("{\"ok\":true}").build()
        );

        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Execution execution = new AgentLlmProperties.Execution();
        execution.setMaxToolCalls(1);
        execution.setFailFast(true);
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        runtime.setExecution(execution);
        properties.setRuntime(runtime);
        when(localConfigLoader.current()).thenReturn(Optional.of(properties));
        when(stateStore.getToolCallCount("run-3")).thenReturn(0, 1, 1, 1, 1);

        WorkflowExecutionResult result = executor.execute(request("run-3", planWithTools(2), properties));

        assertFalse(result.isSuccess());
        verify(eventService).append(eq("run-3"), eq("u1"), eq("TOOL_CALL_LIMIT_REACHED"), anyMap());
        verify(toolRouter, times(1)).invokeWithMeta(eq("searchStock"), anyMap());
    }

    private LinearWorkflowExecutor.WorkflowRequest request(String runId, TodoPlan plan, AgentLlmProperties properties) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("u1");
        return LinearWorkflowExecutor.WorkflowRequest.builder()
                .run(run)
                .userId("u1")
                .userGoal("goal")
                .todoPlan(plan)
                .model(model)
                .toolSpecifications(List.of(ToolSpecification.builder().name("searchStock").description("d").build()))
                .endpointName("ep")
                .endpointBaseUrl("base")
                .modelName("m")
                .build();
    }

    private TodoPlan planWithTools(int count) {
        TodoPlan plan = new TodoPlan();
        for (int i = 1; i <= count; i++) {
            plan.getItems().add(TodoItem.builder()
                    .id("todo_" + i)
                    .sequence(i)
                    .type(TodoType.TOOL_CALL)
                    .toolName("searchStock")
                    .params(Map.of("keyword", "k" + i))
                    .executionMode(ExecutionMode.AUTO)
                    .status(TodoStatus.PENDING)
                    .build());
        }
        return plan;
    }
}

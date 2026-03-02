package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.agent.tool.MarketDataTools;
import world.willfrog.agent.tool.PythonSandboxTools;
import world.willfrog.agent.workflow.LinearWorkflowExecutor;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agent.workflow.TodoPlan;
import world.willfrog.agent.workflow.TodoPlanner;
import world.willfrog.agent.workflow.WorkflowExecutionResult;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class AgentRunExecutorTest {

    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentEventService eventService;
    @Mock
    private AgentAiServiceFactory aiServiceFactory;
    @Mock
    private MarketDataTools marketDataTools;
    @Mock
    private PythonSandboxTools pythonSandboxTools;
    @Mock
    private AgentRunStateStore stateStore;
    @Mock
    private AgentObservabilityService observabilityService;
    @Mock
    private AgentCreditService creditService;
    @Mock
    private TodoPlanner todoPlanner;
    @Mock
    private LinearWorkflowExecutor workflowExecutor;
    @Mock
    private ChatLanguageModel chatLanguageModel;
    @Mock
    private AgentMessageService messageService;

    private AgentRunExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new AgentRunExecutor(
                runMapper,
                eventService,
                aiServiceFactory,
                marketDataTools,
                pythonSandboxTools,
                stateStore,
                observabilityService,
                creditService,
                todoPlanner,
                workflowExecutor,
                messageService,
                new ObjectMapper(),
                new SimpleMeterRegistry()
        );
        executor.init();

        when(eventService.extractEndpointName(anyString())).thenReturn("");
        when(eventService.extractModelName(anyString())).thenReturn("");
        when(eventService.extractCaptureLlmRequests(anyString())).thenReturn(false);
        when(eventService.extractDebugMode(anyString())).thenReturn(false);
        when(eventService.extractOpenRouterProviderOrder(anyString())).thenReturn(List.of());
        when(eventService.extractUserGoal(anyString())).thenReturn("goal");
        when(eventService.extractRunConfig(anyString())).thenReturn(AgentEventService.RunConfig.defaults());

        when(aiServiceFactory.resolveLlm(anyString(), anyString()))
                .thenReturn(new AgentLlmResolver.ResolvedLlm("ep", "base", "model", "", null));
        when(aiServiceFactory.buildChatModelWithProviderOrder(any(), any())).thenReturn(chatLanguageModel);
        lenient().when(creditService.calculateRunTotalCredits(anyString(), anyString(), any())).thenReturn(0);
    }

    @Test
    void execute_shouldMarkCompletedWhenWorkflowSuccess() {
        AgentRun run = run("run-ok");
        when(runMapper.findById("run-ok")).thenReturn(run);
        when(eventService.isRunnable("run-ok", "u1")).thenReturn(true);

        TodoPlan plan = new TodoPlan();
        plan.setItems(List.of(TodoItem.builder().id("todo_1").sequence(1).build()));
        when(todoPlanner.plan(any())).thenReturn(plan);
        when(workflowExecutor.execute(any())).thenReturn(WorkflowExecutionResult.builder()
                .success(true)
                .paused(false)
                .finalAnswer("answer")
                .completedItems(plan.getItems())
                .context(Map.of())
                .toolCallsUsed(1)
                .build());
        when(observabilityService.attachObservabilityToSnapshot(anyString(), anyString(), any())).thenReturn("{}");

        executor.execute("run-ok");

        verify(runMapper).updateSnapshot(eq("run-ok"), eq("u1"), eq(AgentRunStatus.COMPLETED), anyString(), eq(true), eq(null));
        verify(eventService).append(eq("run-ok"), eq("u1"), eq("WORKFLOW_COMPLETED"), anyMap());
        verify(creditService).recordRunConsumeLedger(eq("run-ok"), eq("u1"), eq(0));
    }

    @Test
    void execute_shouldMarkFailedWhenWorkflowFailed() {
        AgentRun run = run("run-fail");
        when(runMapper.findById("run-fail")).thenReturn(run);
        when(eventService.isRunnable("run-fail", "u1")).thenReturn(true);

        TodoPlan plan = new TodoPlan();
        plan.setItems(List.of(TodoItem.builder().id("todo_1").sequence(1).build()));
        when(todoPlanner.plan(any())).thenReturn(plan);
        when(workflowExecutor.execute(any())).thenReturn(WorkflowExecutionResult.builder()
                .success(false)
                .paused(false)
                .failureReason("boom")
                .finalAnswer("")
                .completedItems(plan.getItems())
                .context(Map.of())
                .toolCallsUsed(1)
                .build());
        when(observabilityService.attachObservabilityToSnapshot(anyString(), anyString(), any())).thenReturn("{}");

        executor.execute("run-fail");

        verify(runMapper).updateSnapshot(eq("run-fail"), eq("u1"), eq(AgentRunStatus.FAILED), anyString(), eq(true), eq("boom"));
        verify(eventService).append(eq("run-fail"), eq("u1"), eq("WORKFLOW_FAILED"), anyMap());
    }

    @Test
    void execute_shouldExcludeExecutePythonWhenCodeInterpreterDisabled() {
        AgentRun run = run("run-no-code");
        when(runMapper.findById("run-no-code")).thenReturn(run);
        when(eventService.isRunnable("run-no-code", "u1")).thenReturn(true);
        when(eventService.extractRunConfig(anyString()))
                .thenReturn(new AgentEventService.RunConfig(false, false, 0, false));

        TodoPlan plan = new TodoPlan();
        plan.setItems(List.of(TodoItem.builder().id("todo_1").sequence(1).build()));
        when(todoPlanner.plan(any())).thenReturn(plan);
        when(workflowExecutor.execute(any())).thenReturn(WorkflowExecutionResult.builder()
                .success(true)
                .paused(false)
                .finalAnswer("answer")
                .completedItems(plan.getItems())
                .context(Map.of())
                .toolCallsUsed(1)
                .build());
        when(observabilityService.attachObservabilityToSnapshot(anyString(), anyString(), any())).thenReturn("{}");

        executor.execute("run-no-code");

        ArgumentCaptor<TodoPlanner.PlanRequest> captor = ArgumentCaptor.forClass(TodoPlanner.PlanRequest.class);
        verify(todoPlanner).plan(captor.capture());
        List<String> toolNames = captor.getValue().getToolSpecifications().stream()
                .map(ToolSpecification::name)
                .toList();
        assertFalse(toolNames.contains("executePython"));
    }

    @Test
    void execute_shouldKeepExecutePythonWhenRunConfigDefaultEnabled() {
        AgentRun run = run("run-default");
        when(runMapper.findById("run-default")).thenReturn(run);
        when(eventService.isRunnable("run-default", "u1")).thenReturn(true);

        TodoPlan plan = new TodoPlan();
        plan.setItems(List.of(TodoItem.builder().id("todo_1").sequence(1).build()));
        when(todoPlanner.plan(any())).thenReturn(plan);
        when(workflowExecutor.execute(any())).thenReturn(WorkflowExecutionResult.builder()
                .success(true)
                .paused(false)
                .finalAnswer("answer")
                .completedItems(plan.getItems())
                .context(Map.of())
                .toolCallsUsed(1)
                .build());
        when(observabilityService.attachObservabilityToSnapshot(anyString(), anyString(), any())).thenReturn("{}");

        executor.execute("run-default");

        ArgumentCaptor<TodoPlanner.PlanRequest> captor = ArgumentCaptor.forClass(TodoPlanner.PlanRequest.class);
        verify(todoPlanner).plan(captor.capture());
        List<String> toolNames = captor.getValue().getToolSpecifications().stream()
                .map(ToolSpecification::name)
                .toList();
        assertTrue(toolNames.contains("executePython"));
    }

    private AgentRun run(String id) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setUserId("u1");
        run.setStatus(AgentRunStatus.RECEIVED);
        run.setExt("{}");
        run.setSnapshotJson("{}");
        return run;
    }
}

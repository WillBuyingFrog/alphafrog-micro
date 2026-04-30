package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
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
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.config.RunStageConfig;
import world.willfrog.agent.config.StageLlmConfig;
import world.willfrog.agent.tool.MarketDataTools;
import world.willfrog.agent.tool.PythonSandboxTools;
import world.willfrog.agent.tool.RagTools;
import world.willfrog.agent.tool.SearchTools;

import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agent.workflow.TodoPlan;
import world.willfrog.agent.workflow.TodoPlanner;
import world.willfrog.agent.workflow.WorkflowExecutionResult;
import world.willfrog.agent.workflow.WorkflowExecutor;
import world.willfrog.agent.workflow.WorkflowExecutorFactory;
import world.willfrog.agent.workflow.WorkflowRequest;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private RagTools ragTools;
    @Mock
    private SearchTools searchTools;
    @Mock
    private AgentRunStateStore stateStore;
    @Mock
    private AgentObservabilityService observabilityService;
    @Mock
    private AgentCreditService creditService;
    @Mock
    private TodoPlanner todoPlanner;
    @Mock
    private WorkflowExecutorFactory workflowExecutorFactory;
    @Mock
    private WorkflowExecutor workflowExecutor;
    @Mock
    private ChatModel chatLanguageModel;
    @Mock
    private AgentMessageService messageService;
    @Mock
    private AgentLlmLocalConfigLoader localConfigLoader;
    @Mock
    private StageConfigResolver stageConfigResolver;
    @Mock
    private StageConfigValidator stageConfigValidator;

    private AgentRunExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new AgentRunExecutor(
                runMapper,
                eventService,
                aiServiceFactory,
                marketDataTools,
                pythonSandboxTools,
                ragTools,
                searchTools,
                stateStore,
                observabilityService,
                creditService,
                todoPlanner,
                workflowExecutorFactory,
                messageService,
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                localConfigLoader,
                new AgentLlmProperties(),
                stageConfigResolver,
                stageConfigValidator
        );
        executor.init();

        when(eventService.extractEndpointName(anyString())).thenReturn("");
        when(eventService.extractModelName(anyString())).thenReturn("");
        when(eventService.extractCaptureLlmRequests(anyString())).thenReturn(false);
        when(eventService.extractDebugMode(anyString())).thenReturn(false);
        when(eventService.extractOpenRouterProviderOrder(anyString())).thenReturn(List.of());
        when(eventService.extractUserGoal(anyString())).thenReturn("goal");
        when(eventService.extractRunConfig(anyString())).thenReturn(AgentEventService.RunConfig.defaults());
        when(stageConfigResolver.resolve(anyString())).thenReturn(new RunStageConfig());

        when(aiServiceFactory.resolveLlm(anyString(), anyString()))
                .thenReturn(new AgentLlmResolver.ResolvedLlm("ep", "base", "model", "", null, List.of()));
        when(aiServiceFactory.buildChatModelWithProviderOrder(any(), any())).thenReturn(chatLanguageModel);
        lenient().when(creditService.calculateRunTotalCredits(anyString(), anyString(), any())).thenReturn(0);
        lenient().when(workflowExecutorFactory.select(any())).thenReturn(workflowExecutor);
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
                .thenReturn(new AgentEventService.RunConfig(
                        false,
                        AgentContext.WebSearchConfig.empty(),
                        false,
                        0,
                        false
                ));

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

    @Test
    void execute_shouldPreferRequestedModelOverLocalStageFallback() {
        AgentRun run = run("run-request-model");
        when(runMapper.findById("run-request-model")).thenReturn(run);
        when(eventService.isRunnable("run-request-model", "u1")).thenReturn(true);
        when(eventService.extractEndpointName(anyString())).thenReturn("openrouter");
        when(eventService.extractModelName(anyString())).thenReturn("moonshotai/kimi-k2.6");

        RunStageConfig localStageConfig = new RunStageConfig();
        localStageConfig.setPlanning(stage("openrouter", "moonshotai/kimi-k2.5"));
        localStageConfig.setExecution(stage("openrouter", "moonshotai/kimi-k2.5"));
        when(stageConfigResolver.resolve(anyString())).thenReturn(localStageConfig);
        when(aiServiceFactory.resolveLlm(anyString(), anyString())).thenAnswer(inv -> new AgentLlmResolver.ResolvedLlm(
                inv.getArgument(0),
                "base",
                inv.getArgument(1),
                "",
                null,
                List.of()
        ));

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

        executor.execute("run-request-model");

        ArgumentCaptor<TodoPlanner.PlanRequest> planCaptor = ArgumentCaptor.forClass(TodoPlanner.PlanRequest.class);
        verify(todoPlanner).plan(planCaptor.capture());
        assertEquals("openrouter", planCaptor.getValue().getEndpointName());
        assertEquals("moonshotai/kimi-k2.6", planCaptor.getValue().getModelName());

        ArgumentCaptor<WorkflowRequest> workflowCaptor = ArgumentCaptor.forClass(WorkflowRequest.class);
        verify(workflowExecutor).execute(workflowCaptor.capture());
        assertEquals("openrouter", workflowCaptor.getValue().getEndpointName());
        assertEquals("moonshotai/kimi-k2.6", workflowCaptor.getValue().getModelName());
    }

    @Test
    void execute_shouldLetPartialPlanningStageOverrideOnlyProvidedField() {
        AgentRun run = run("run-partial-stage");
        run.setExt("""
                {"stage_config_json":{"planning":{"modelName":"stage-model","temperature":0.2}}}
                """);
        when(runMapper.findById("run-partial-stage")).thenReturn(run);
        when(eventService.isRunnable("run-partial-stage", "u1")).thenReturn(true);
        when(eventService.extractEndpointName(anyString())).thenReturn("request-endpoint");
        when(eventService.extractModelName(anyString())).thenReturn("request-model");

        RunStageConfig stageConfig = new RunStageConfig();
        StageLlmConfig planning = stage("local-endpoint", "stage-model");
        planning.setTemperature(0.2D);
        stageConfig.setPlanning(planning);
        stageConfig.setExecution(stage("local-endpoint", "local-model"));
        when(stageConfigResolver.resolve(anyString())).thenReturn(stageConfig);
        when(aiServiceFactory.resolveLlm(anyString(), anyString())).thenAnswer(inv -> new AgentLlmResolver.ResolvedLlm(
                inv.getArgument(0),
                "base",
                inv.getArgument(1),
                "",
                null,
                List.of()
        ));
        stubSuccessfulWorkflow();

        executor.execute("run-partial-stage");

        ArgumentCaptor<TodoPlanner.PlanRequest> planCaptor = ArgumentCaptor.forClass(TodoPlanner.PlanRequest.class);
        verify(todoPlanner).plan(planCaptor.capture());
        assertEquals("request-endpoint", planCaptor.getValue().getEndpointName());
        assertEquals("stage-model", planCaptor.getValue().getModelName());

        ArgumentCaptor<WorkflowRequest> workflowCaptor = ArgumentCaptor.forClass(WorkflowRequest.class);
        verify(workflowExecutor).execute(workflowCaptor.capture());
        assertEquals("request-endpoint", workflowCaptor.getValue().getEndpointName());
        assertEquals("request-model", workflowCaptor.getValue().getModelName());
    }

    @Test
    void execute_shouldPreferCompleteExplicitPlanningStageOverRunRequest() {
        AgentRun run = run("run-full-stage");
        run.setExt("""
                {"stage_config_json":{"planning":{"endpointName":"stage-endpoint","modelName":"stage-model"}}}
                """);
        when(runMapper.findById("run-full-stage")).thenReturn(run);
        when(eventService.isRunnable("run-full-stage", "u1")).thenReturn(true);
        when(eventService.extractEndpointName(anyString())).thenReturn("request-endpoint");
        when(eventService.extractModelName(anyString())).thenReturn("request-model");

        RunStageConfig stageConfig = new RunStageConfig();
        stageConfig.setPlanning(stage("stage-endpoint", "stage-model"));
        stageConfig.setExecution(stage("local-endpoint", "local-model"));
        when(stageConfigResolver.resolve(anyString())).thenReturn(stageConfig);
        when(aiServiceFactory.resolveLlm(anyString(), anyString())).thenAnswer(inv -> new AgentLlmResolver.ResolvedLlm(
                inv.getArgument(0),
                "base",
                inv.getArgument(1),
                "",
                null,
                List.of()
        ));
        stubSuccessfulWorkflow();

        executor.execute("run-full-stage");

        ArgumentCaptor<TodoPlanner.PlanRequest> planCaptor = ArgumentCaptor.forClass(TodoPlanner.PlanRequest.class);
        verify(todoPlanner).plan(planCaptor.capture());
        assertEquals("stage-endpoint", planCaptor.getValue().getEndpointName());
        assertEquals("stage-model", planCaptor.getValue().getModelName());
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

    private StageLlmConfig stage(String endpointName, String modelName) {
        StageLlmConfig config = new StageLlmConfig();
        config.setEndpointName(endpointName);
        config.setModelName(modelName);
        return config;
    }

    private void stubSuccessfulWorkflow() {
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
    }
}

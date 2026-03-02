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
import world.willfrog.agent.service.AgentMessageService;
import world.willfrog.agent.service.AgentContextCompressor;
import world.willfrog.agent.service.AgentAiServiceFactory;
import world.willfrog.agent.service.AgentLlmResolver;
import world.willfrog.agent.tool.ToolRouter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    @Mock
    private AgentMessageService messageService;
    @Mock
    private AgentContextCompressor contextCompressor;
    @Mock
    private AgentAiServiceFactory aiServiceFactory;
    @Mock
    private PythonStaticPrecheckService pythonStaticPrecheckService;
    @Mock
    private PythonSemanticJudgeService pythonSemanticJudgeService;

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
                messageService,
                contextCompressor,
                aiServiceFactory,
                pythonStaticPrecheckService,
                pythonSemanticJudgeService,
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
        lenient().when(pythonStaticPrecheckService.check(anyString(), anyString(), anyMap()))
                .thenReturn(PythonStaticPrecheckService.Result.builder().passed(true).build());
        lenient().when(pythonSemanticJudgeService.judge(any()))
                .thenReturn(PythonSemanticJudgeService.Result.pass("OK", "", Map.of()));

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

    @Test
    void execute_shouldSkipSandboxWhenStaticPrecheckFails() {
        when(eventService.isRunnable("run-precheck", "u1")).thenReturn(true);
        when(pythonStaticPrecheckService.check(anyString(), anyString(), anyMap())).thenReturn(
                PythonStaticPrecheckService.Result.builder()
                        .passed(false)
                        .errorCode("STATIC_PRECHECK_FAILED")
                        .message("dataset_id 不能为空")
                        .category(TodoFailureCategory.STATIC)
                        .report(Map.of("issues", List.of("dataset_id 不能为空")))
                        .build()
        );

        WorkflowExecutionResult result = executor.execute(request("run-precheck", planExecutePython("todo_1"), new AgentLlmProperties()));

        assertFalse(result.isSuccess());
        verify(toolRouter, never()).invokeWithMeta(eq("executePython"), anyMap());
    }

    @Test
    void execute_shouldUseStaticFixModelForStaticRecovery() {
        when(eventService.isRunnable("run-static-fix", "u1")).thenReturn(true);
        AgentLlmProperties properties = runtimeConfig(true, false, 2, 2, 1, 2);
        properties.getRuntime().getExecution().setStaticFixEndpoint("openrouter");
        properties.getRuntime().getExecution().setStaticFixModel("openai/gpt-5.2");
        properties.getRuntime().getExecution().setStaticFixTemperature(0.0);
        when(localConfigLoader.current()).thenReturn(Optional.of(properties));

        when(pythonStaticPrecheckService.check(anyString(), anyString(), anyMap())).thenReturn(
                PythonStaticPrecheckService.Result.builder()
                        .passed(false)
                        .errorCode("STATIC_PRECHECK_FAILED")
                        .message("代码引用了 dataset_id 变量但未定义")
                        .category(TodoFailureCategory.STATIC)
                        .report(Map.of("issues", List.of("dataset_id")))
                        .build(),
                PythonStaticPrecheckService.Result.builder().passed(true).build()
        );

        when(toolRouter.invokeWithMeta(eq("executePython"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder()
                        .success(true)
                        .output("{\"ok\":true,\"tool\":\"executePython\",\"data\":{\"stdout\":\"ok\"}}")
                        .build()
        );

        ChatLanguageModel staticFixModel = mock(ChatLanguageModel.class);
        when(aiServiceFactory.resolveLlm("openrouter", "openai/gpt-5.2")).thenReturn(
                new AgentLlmResolver.ResolvedLlm("openrouter", "https://openrouter.ai/api/v1", "openai/gpt-5.2", "k", null)
        );
        when(aiServiceFactory.buildChatModelWithProviderOrderAndTemperature(any(), any(), any())).thenReturn(staticFixModel);
        Response<AiMessage> staticFixRecoveryResponse = mockResponse("{\"params\":{\"dataset_ids\":\"d1\",\"code\":\"print(1)\"}}");
        when(staticFixModel.generate(any(List.class))).thenReturn(staticFixRecoveryResponse);

        WorkflowExecutionResult result = executor.execute(request("run-static-fix", planExecutePython("todo_1"), properties));

        assertTrue(result.isSuccess());
        verify(aiServiceFactory).resolveLlm("openrouter", "openai/gpt-5.2");
        verify(staticFixModel, times(1)).generate(any(List.class));
    }

    @Test
    void execute_shouldRetryOnSemanticRejectAndRespectBudget() {
        when(eventService.isRunnable("run-semantic", "u1")).thenReturn(true);
        AgentLlmProperties properties = runtimeConfig(true, true, 2, 2, 1, 2);
        when(localConfigLoader.current()).thenReturn(Optional.of(properties));

        when(pythonStaticPrecheckService.check(anyString(), anyString(), anyMap()))
                .thenReturn(PythonStaticPrecheckService.Result.builder().passed(true).build());
        when(toolRouter.invokeWithMeta(eq("executePython"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder()
                        .success(true)
                        .output("{\"ok\":true,\"tool\":\"executePython\",\"data\":{\"stdout\":\"v\"}}")
                        .build()
        );
        when(pythonSemanticJudgeService.judge(any())).thenReturn(
                PythonSemanticJudgeService.Result.reject("NUMERIC_ANOMALY", "HIGH", "收益率异常", Map.of("k", "v")),
                PythonSemanticJudgeService.Result.pass("OK", "通过", Map.of("k", "v2"))
        );
        Response<AiMessage> semanticRecoveryResponse = mockResponse("{\"params\":{\"dataset_ids\":\"d1\",\"code\":\"print(2)\"}}");
        Response<AiMessage> semanticFinalResponse = mockResponse("final");
        when(model.generate(any(List.class))).thenReturn(semanticRecoveryResponse, semanticFinalResponse);

        WorkflowExecutionResult result = executor.execute(request("run-semantic", planExecutePython("todo_1"), properties));

        assertTrue(result.isSuccess());
        verify(pythonSemanticJudgeService, times(2)).judge(any());
        verify(observabilityService, times(2)).recordSemanticJudgeCall(eq("run-semantic"), anyBoolean());
        verify(toolRouter, times(2)).invokeWithMeta(eq("executePython"), anyMap());
    }

    @Test
    void execute_shouldStopWhenStaticRetryBudgetExceeded() {
        when(eventService.isRunnable("run-static-budget", "u1")).thenReturn(true);
        AgentLlmProperties properties = runtimeConfig(true, false, 1, 3, 1, 4);
        when(localConfigLoader.current()).thenReturn(Optional.of(properties));
        when(pythonStaticPrecheckService.check(anyString(), anyString(), anyMap())).thenReturn(
                PythonStaticPrecheckService.Result.builder()
                        .passed(false)
                        .errorCode("STATIC_PRECHECK_FAILED")
                        .message("dataset_id 不能为空")
                        .category(TodoFailureCategory.STATIC)
                        .build()
        );
        Response<AiMessage> staticBudgetRecoveryResponse = mockResponse("{\"params\":{\"dataset_ids\":\"d1\",\"code\":\"print(1)\"}}");
        Response<AiMessage> staticBudgetFinalResponse = mockResponse("final");
        when(model.generate(any(List.class))).thenReturn(staticBudgetRecoveryResponse, staticBudgetFinalResponse);

        WorkflowExecutionResult result = executor.execute(request("run-static-budget", planExecutePython("todo_1"), properties));

        assertFalse(result.isSuccess());
        verify(toolRouter, never()).invokeWithMeta(eq("executePython"), anyMap());
        ArgumentCaptor<Map<String, Object>> retryPayload = ArgumentCaptor.forClass(Map.class);
        verify(eventService, times(1)).append(eq("run-static-budget"), eq("u1"), eq("TODO_RETRY"), retryPayload.capture());
        assertEquals("STATIC", retryPayload.getValue().get("failure_category"));
    }

    @Test
    void execute_shouldStopWhenSemanticRetryBudgetExceeded() {
        when(eventService.isRunnable("run-semantic-budget", "u1")).thenReturn(true);
        AgentLlmProperties properties = runtimeConfig(true, true, 2, 2, 1, 4);
        when(localConfigLoader.current()).thenReturn(Optional.of(properties));

        when(pythonStaticPrecheckService.check(anyString(), anyString(), anyMap()))
                .thenReturn(PythonStaticPrecheckService.Result.builder().passed(true).build());
        when(toolRouter.invokeWithMeta(eq("executePython"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder()
                        .success(true)
                        .output("{\"ok\":true,\"tool\":\"executePython\",\"data\":{\"stdout\":\"v\"}}")
                        .build()
        );
        when(pythonSemanticJudgeService.judge(any())).thenReturn(
                PythonSemanticJudgeService.Result.reject("NUMERIC_ANOMALY", "HIGH", "收益率异常", Map.of()),
                PythonSemanticJudgeService.Result.reject("NUMERIC_ANOMALY", "HIGH", "收益率仍异常", Map.of())
        );
        Response<AiMessage> semanticBudgetRecoveryResponse = mockResponse("{\"params\":{\"dataset_ids\":\"d1\",\"code\":\"print(2)\"}}");
        Response<AiMessage> semanticBudgetFinalResponse = mockResponse("final");
        when(model.generate(any(List.class))).thenReturn(semanticBudgetRecoveryResponse, semanticBudgetFinalResponse);

        WorkflowExecutionResult result = executor.execute(request("run-semantic-budget", planExecutePython("todo_1"), properties));

        assertFalse(result.isSuccess());
        verify(toolRouter, times(2)).invokeWithMeta(eq("executePython"), anyMap());
        verify(eventService, times(1)).append(eq("run-semantic-budget"), eq("u1"), eq("TODO_RETRY"), anyMap());
    }

    @Test
    void execute_shouldStopWhenRuntimeRetryBudgetExceeded() {
        when(eventService.isRunnable("run-runtime-budget", "u1")).thenReturn(true);
        AgentLlmProperties properties = runtimeConfig(true, false, 2, 1, 1, 4);
        when(localConfigLoader.current()).thenReturn(Optional.of(properties));
        when(toolRouter.invokeWithMeta(eq("searchStock"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder()
                        .success(false)
                        .output("{\"ok\":false,\"error\":{\"message\":\"provider timeout\"}}")
                        .build()
        );
        Response<AiMessage> runtimeRecoveryResponse = mockResponse("{\"params\":{\"keyword\":\"k-retry\"}}");
        Response<AiMessage> runtimeFinalResponse = mockResponse("final");
        when(model.generate(any(List.class))).thenReturn(runtimeRecoveryResponse, runtimeFinalResponse);

        WorkflowExecutionResult result = executor.execute(request("run-runtime-budget", planWithTools(1), properties));

        assertFalse(result.isSuccess());
        verify(toolRouter, times(2)).invokeWithMeta(eq("searchStock"), anyMap());
        verify(eventService, times(1)).append(eq("run-runtime-budget"), eq("u1"), eq("TODO_RETRY"), anyMap());
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
                .toolSpecifications(List.of(
                        ToolSpecification.builder().name("searchStock").description("d").build(),
                        ToolSpecification.builder().name("executePython").description("d").build()
                ))
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

    private TodoPlan planExecutePython(String todoId) {
        TodoPlan plan = new TodoPlan();
        plan.getItems().add(TodoItem.builder()
                .id(todoId)
                .sequence(1)
                .type(TodoType.TOOL_CALL)
                .toolName("executePython")
                .params(Map.of(
                        "dataset_ids", "d1",
                        "code", "print('ok')"
                ))
                .executionMode(ExecutionMode.AUTO)
                .status(TodoStatus.PENDING)
                .build());
        return plan;
    }

    private AgentLlmProperties runtimeConfig(boolean staticPrecheckEnabled,
                                             boolean semanticEnabled,
                                             int maxStaticRecoveryRetries,
                                             int maxRuntimeRecoveryRetries,
                                             int maxSemanticRecoveryRetries,
                                             int maxRetriesPerTodo) {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Execution execution = new AgentLlmProperties.Execution();
        execution.setStaticPrecheckEnabled(staticPrecheckEnabled);
        execution.setMaxStaticRecoveryRetries(maxStaticRecoveryRetries);
        execution.setMaxRuntimeRecoveryRetries(maxRuntimeRecoveryRetries);
        execution.setMaxSemanticRecoveryRetries(maxSemanticRecoveryRetries);
        execution.setMaxTotalRecoveryRetries(maxRetriesPerTodo - 1);
        execution.setMaxRetriesPerTodo(maxRetriesPerTodo);

        AgentLlmProperties.Judge judge = new AgentLlmProperties.Judge();
        judge.setSemanticEnabled(semanticEnabled);
        judge.setFailOpen(true);
        judge.setMaxAttempts(1);
        judge.setBlockOnInsufficientEvidence(false);
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        runtime.setExecution(execution);
        runtime.setJudge(judge);
        properties.setRuntime(runtime);
        return properties;
    }

    @SuppressWarnings("unchecked")
    private Response<AiMessage> mockResponse(String text) {
        Response<AiMessage> response = mock(Response.class);
        AiMessage aiMessage = mock(AiMessage.class);
        when(aiMessage.text()).thenReturn(text);
        when(response.content()).thenReturn(aiMessage);
        when(response.tokenUsage()).thenReturn(null);
        return response;
    }
}

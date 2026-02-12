package world.willfrog.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.service.AgentLlmRequestSnapshotBuilder;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentRunStateStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DagTaskExecutorTest {

    @Mock
    private ParallelTaskExecutor parallelTaskExecutor;
    @Mock
    private AgentPromptService promptService;
    @Mock
    private AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    @Mock
    private AgentObservabilityService observabilityService;
    @Mock
    private AgentEventService eventService;
    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentRunStateStore stateStore;
    @Mock
    private AgentLlmLocalConfigLoader localConfigLoader;
    @Mock
    private ChatLanguageModel chatLanguageModel;

    private DagTaskExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new DagTaskExecutor(
                parallelTaskExecutor,
                promptService,
                llmRequestSnapshotBuilder,
                observabilityService,
                eventService,
                runMapper,
                stateStore,
                localConfigLoader,
                new AgentLlmProperties(),
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(executor, "maxTasks", 6);
        ReflectionTestUtils.setField(executor, "subAgentMaxSteps", 6);
        ReflectionTestUtils.setField(executor, "maxParallelTasks", -1);
        ReflectionTestUtils.setField(executor, "maxSubAgents", -1);
        ReflectionTestUtils.setField(executor, "defaultMaxLocalReplans", 1);

        lenient().when(localConfigLoader.current()).thenReturn(Optional.empty());
        lenient().when(llmRequestSnapshotBuilder.buildChatCompletionsRequest(anyString(), anyString(), anyString(), any(), any(), anyMap()))
                .thenReturn(Map.of());
    }

    @Test
    void execute_shouldReturnSuccessWhenAllTasksDone() {
        AgentRun run = run("run-s");
        ParallelPlan plan = planWithSingleTask();
        ParallelTaskResult taskResult = ParallelTaskResult.builder()
                .taskId("t1")
                .type("tool")
                .success(true)
                .output("{}")
                .build();

        when(stateStore.loadTaskResults("run-s")).thenReturn(Map.of());
        when(parallelTaskExecutor.execute(any(), anyString(), anyString(), any(), any(), anyInt(), anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Map.of("t1", taskResult));
        when(eventService.isRunnable("run-s", "u1")).thenReturn(true);
        when(promptService.parallelFinalSystemPrompt()).thenReturn("final");
        Response<AiMessage> finalResponse = mockResponse("final answer");
        when(chatLanguageModel.generate(any(List.class))).thenReturn(finalResponse);

        DagTaskExecutor.ExecutionResult result = executor.execute(request(run, plan));

        assertTrue(result.isSuccess());
        assertEquals("final answer", result.getFinalAnswer());
    }

    @Test
    void execute_shouldReturnPausedWhenRunNotRunnable() {
        AgentRun run = run("run-p");
        ParallelPlan plan = planWithSingleTask();

        when(stateStore.loadTaskResults("run-p")).thenReturn(Map.of());
        when(parallelTaskExecutor.execute(any(), anyString(), anyString(), any(), any(), anyInt(), anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Map.of());
        when(eventService.isRunnable("run-p", "u1")).thenReturn(false);

        DagTaskExecutor.ExecutionResult result = executor.execute(request(run, plan));

        assertTrue(result.isPaused());
    }

    @Test
    void execute_shouldFailAfterPatchExhausted() {
        AgentRun run = run("run-f");
        ParallelPlan plan = planWithSingleTask();
        ParallelTaskResult failedResult = ParallelTaskResult.builder()
                .taskId("t1")
                .type("tool")
                .success(false)
                .output("error")
                .error("boom")
                .build();

        when(stateStore.loadTaskResults("run-f")).thenReturn(Map.of());
        when(parallelTaskExecutor.execute(any(), anyString(), anyString(), any(), any(), anyInt(), anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Map.of("t1", failedResult))
                .thenReturn(Map.of("t1", failedResult));
        when(eventService.isRunnable("run-f", "u1")).thenReturn(true);
        when(promptService.parallelPatchPlannerSystemPrompt()).thenReturn("patch");
        Response<AiMessage> patchResponse = mockResponse("{}");
        when(chatLanguageModel.generate(any(List.class))).thenReturn(patchResponse);

        DagTaskExecutor.ExecutionResult result = executor.execute(request(run, plan));

        assertFalse(result.isSuccess());
        assertEquals("local_replan_exhausted", result.getFailureReason());
        verify(eventService).append(eq("run-f"), eq("u1"), eq("PLAN_PATCH_EXHAUSTED"), anyMap());
    }

    private DagTaskExecutor.ExecutionRequest request(AgentRun run, ParallelPlan plan) {
        return DagTaskExecutor.ExecutionRequest.builder()
                .run(run)
                .userId("u1")
                .userGoal("goal")
                .plan(plan)
                .model(chatLanguageModel)
                .toolWhitelist(Set.of("searchStock"))
                .endpointName("ep")
                .endpointBaseUrl("base")
                .modelName("m1")
                .build();
    }

    private ParallelPlan planWithSingleTask() {
        ParallelPlan plan = new ParallelPlan();
        ParallelPlan.PlanTask task = new ParallelPlan.PlanTask();
        task.setId("t1");
        task.setType("tool");
        task.setTool("searchStock");
        task.setDependsOn(List.of());
        plan.setTasks(List.of(task));
        return plan;
    }

    private AgentRun run(String id) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setUserId("u1");
        return run;
    }

    private Response<AiMessage> mockResponse(String text) {
        @SuppressWarnings("unchecked")
        Response<AiMessage> response = mock(Response.class);
        AiMessage aiMessage = mock(AiMessage.class);
        when(aiMessage.text()).thenReturn(text);
        when(response.content()).thenReturn(aiMessage);
        when(response.tokenUsage()).thenReturn(null);
        return response;
    }
}

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
class PlanExecutionPlannerTest {

    @Mock
    private PlanJudgeService planJudgeService;
    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentEventService eventService;
    @Mock
    private AgentRunStateStore stateStore;
    @Mock
    private AgentPromptService promptService;
    @Mock
    private AgentObservabilityService observabilityService;
    @Mock
    private AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    @Mock
    private AgentLlmLocalConfigLoader localConfigLoader;
    @Mock
    private ChatLanguageModel chatLanguageModel;

    private PlanExecutionPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new PlanExecutionPlanner(
                new PlanComplexityScorer(),
                planJudgeService,
                new DagAnalyzer(),
                runMapper,
                eventService,
                stateStore,
                promptService,
                observabilityService,
                llmRequestSnapshotBuilder,
                localConfigLoader,
                new AgentLlmProperties(),
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(planner, "maxTasks", 6);
        ReflectionTestUtils.setField(planner, "subAgentMaxSteps", 6);
        ReflectionTestUtils.setField(planner, "maxParallelTasks", -1);
        ReflectionTestUtils.setField(planner, "maxSubAgents", -1);
        ReflectionTestUtils.setField(planner, "defaultCandidatePlanCount", 3);
        ReflectionTestUtils.setField(planner, "defaultComplexityPenaltyLambda", 0.25D);

        lenient().when(localConfigLoader.current()).thenReturn(Optional.empty());
        lenient().when(llmRequestSnapshotBuilder.buildChatCompletionsRequest(anyString(), anyString(), anyString(), any(), any(), anyMap()))
                .thenReturn(Map.of());
    }

    @Test
    void plan_shouldReuseStoredPlanAndEmitAnalyzedEvent() {
        AgentRun run = run("run-1", "{\"tasks\":[{\"id\":\"t1\",\"type\":\"tool\",\"tool\":\"searchStock\",\"dependsOn\":[]}]} ");
        when(stateStore.isPlanOverride("run-1")).thenReturn(false);
        when(stateStore.loadPlan("run-1")).thenReturn(Optional.of(run.getPlanJson()));

        PlanExecutionPlanner.PlanResult result = planner.plan(
                PlanExecutionPlanner.PlanRequest.builder()
                        .run(run)
                        .userId("u1")
                        .userGoal("goal")
                        .model(chatLanguageModel)
                        .toolWhitelist(Set.of("searchStock"))
                        .endpointName("ep")
                        .endpointBaseUrl("base")
                        .modelName("m1")
                        .build()
        );

        assertTrue(result.isPlanValid());
        verify(eventService).append(eq("run-1"), eq("u1"), eq("PLAN_REUSED"), anyMap());
        verify(eventService).append(eq("run-1"), eq("u1"), eq("PLAN_ANALYZED"), anyMap());
        verify(runMapper).updatePlan(eq("run-1"), eq("u1"), any(), anyString());
    }

    @Test
    void plan_shouldSelectBestCandidateByFinalScore() {
        AgentRun run = run("run-2", "{}");
        when(stateStore.isPlanOverride("run-2")).thenReturn(false);
        when(stateStore.loadPlan("run-2")).thenReturn(Optional.empty());
        when(eventService.extractPlannerCandidateCount(anyString())).thenReturn(2);
        when(promptService.parallelPlannerSystemPrompt(anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn("planner-prompt");

        Response<AiMessage> response1 = mockResponse("{\"tasks\":[{\"id\":\"a\",\"type\":\"tool\",\"tool\":\"searchStock\",\"dependsOn\":[]}]}");
        Response<AiMessage> response2 = mockResponse("{\"tasks\":[{\"id\":\"b\",\"type\":\"tool\",\"tool\":\"searchStock\",\"dependsOn\":[]}]}");
        when(chatLanguageModel.generate(any(List.class)))
                .thenReturn(response1)
                .thenReturn(response2);

        when(planJudgeService.evaluate(any()))
                .thenReturn(evaluation(0.2D))
                .thenReturn(evaluation(0.8D));

        PlanExecutionPlanner.PlanResult result = planner.plan(
                PlanExecutionPlanner.PlanRequest.builder()
                        .run(run)
                        .userId("u1")
                        .userGoal("goal")
                        .model(chatLanguageModel)
                        .toolWhitelist(Set.of("searchStock"))
                        .endpointName("ep")
                        .endpointBaseUrl("base")
                        .modelName("m1")
                        .build()
        );

        assertTrue(result.isPlanValid());
        assertEquals(0.8D, result.getPlanScore(), 1e-9);
        verify(eventService).append(eq("run-2"), eq("u1"), eq("PLAN_SELECTED"), anyMap());
        verify(eventService).append(eq("run-2"), eq("u1"), eq("PLAN_ANALYZED"), anyMap());
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

    private PlanJudgeService.Evaluation evaluation(double finalScore) {
        return PlanJudgeService.Evaluation.builder()
                .valid(true)
                .structuralScore(1D)
                .llmJudgeScore(1D)
                .complexityPenalty(0D)
                .finalScore(finalScore)
                .summary(Map.of("judge", "ok"))
                .build();
    }

    private AgentRun run(String id, String planJson) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setUserId("u1");
        run.setPlanJson(planJson);
        run.setExt("{}");
        return run;
    }
}

package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.orchestration.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangchainLinearRunPipelineFailureMappingTest {

    @Test
    void executeRun_shouldEmitBudgetExceededEventWhenMapperSaysSo() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentEventService eventService = mock(AgentEventService.class);
        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);

        AgentRun run = new AgentRun();
        run.setId("run-budget-1");
        run.setUserId("user-1");
        run.setExt("{}");
        when(runMapper.findById("run-budget-1")).thenReturn(run);
        when(eventService.isRunnable("run-budget-1", "user-1")).thenReturn(true);
        when(eventService.extractCaptureLlmRequests(run.getExt())).thenReturn(false);
        when(eventService.extractEndpointName(run.getExt())).thenReturn("openrouter");
        when(eventService.extractModelName(run.getExt())).thenReturn("kimi");
        when(eventService.extractUserGoal(run.getExt())).thenReturn("goal");
        when(eventService.extractRunConfig(run.getExt())).thenReturn(AgentEventService.RunConfig.defaults());
        when(stageModelResolver.resolve(run)).thenReturn(new LangchainRunStageModelResolver.StageModels(
                null, null, null, "openrouter", "kimi", List.of()));

        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        when(planner.plan(any())).thenReturn(LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("t1").sequence(1).description("x").build()))
                .build());

        LangchainLinearWorkflowExecutor linear = mock(LangchainLinearWorkflowExecutor.class);
        when(linear.executePlanned(any(), any())).thenReturn(LangchainLinearWorkflowResult.builder()
                .success(false)
                .failureReason("RUN_BUDGET_EXCEEDED wall_clock_ms: 700000 / 600000")
                .toolCallsUsed(3)
                .build());

        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(1);
        taskExecutor.setMaxPoolSize(1);
        taskExecutor.initialize();

        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                planner,
                linear,
                mock(LangchainDagWorkflowExecutor.class),
                stageModelResolver,
                runMapper,
                eventService,
                new ObjectMapper(),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                new LangchainFailureMapper(),
                taskExecutor
        );

        pipeline.executeRun(run);

        verify(eventService).append(eq("run-budget-1"), eq("user-1"), eq("RUN_BUDGET_EXCEEDED"), any(Map.class));
        taskExecutor.shutdown();
    }
}

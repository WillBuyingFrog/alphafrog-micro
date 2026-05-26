package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.orchestration.dag.LangchainDagWorkflowExecutor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangchainLinearRunPipelineObservabilityTest {

    @Test
    void executeRun_shouldInitializeObservabilityWithCaptureFlagFromExt() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentObservabilityService observabilityService = mock(AgentObservabilityService.class);
        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);

        AgentRun run = new AgentRun();
        run.setId("run-obs-1");
        run.setUserId("user-1");
        run.setExt("{\"captureLlmRequests\":true}");
        when(runMapper.findById("run-obs-1")).thenReturn(run);
        when(eventService.isRunnable("run-obs-1", "user-1")).thenReturn(true);
        when(eventService.extractCaptureLlmRequests(run.getExt())).thenReturn(true);
        when(eventService.extractEndpointName(run.getExt())).thenReturn("openrouter");
        when(eventService.extractModelName(run.getExt())).thenReturn("kimi-k2.6");
        when(eventService.extractUserGoal(run.getExt())).thenReturn("goal");
        when(eventService.extractRunConfig(run.getExt())).thenReturn(AgentEventService.RunConfig.defaults());
        when(stageModelResolver.resolve(run)).thenReturn(new LangchainRunStageModelResolver.StageModels(
                null, null, null, "openrouter-plan", "kimi-k2.5", List.of()));

        @SuppressWarnings("unchecked")
        ObjectProvider<AgentObservabilityService> observabilityProvider = mock(ObjectProvider.class);
        when(observabilityProvider.getIfAvailable()).thenReturn(observabilityService);

        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(1);
        taskExecutor.setMaxPoolSize(1);
        taskExecutor.initialize();

        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        when(planner.plan(any())).thenReturn(LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("t1").sequence(1).description("x").build()))
                .build());
        LangchainLinearWorkflowExecutor linearWorkflowExecutor = mock(LangchainLinearWorkflowExecutor.class);
        when(linearWorkflowExecutor.executePlanned(any(), any())).thenReturn(
                LangchainLinearWorkflowResult.builder().success(true).finalAnswer("ok").build());

        LangchainFollowUpContextSupport followUpContextSupport = mock(LangchainFollowUpContextSupport.class);
        when(followUpContextSupport.resolve(run)).thenReturn(
                new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));

        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                planner,
                linearWorkflowExecutor,
                mock(LangchainDagWorkflowExecutor.class),
                stageModelResolver,
                runMapper,
                eventService,
                new ObjectMapper(),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                observabilityProvider,
                new LangchainFailureMapper(),
                followUpContextSupport,
                mock(world.willfrog.agent.platform.service.AgentMessageService.class),
                mock(LangchainRunExecutionGuard.class),
                taskExecutor
        );

        pipeline.executeRun(run);

        verify(observabilityService).initializeRun(
                eq("run-obs-1"),
                eq("openrouter-plan"),
                eq("kimi-k2.5"),
                eq(true));
        taskExecutor.shutdown();
    }
}

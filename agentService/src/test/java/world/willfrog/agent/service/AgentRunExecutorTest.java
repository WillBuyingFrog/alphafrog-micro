package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.graph.ParallelGraphExecutor;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.agent.tool.MarketDataTools;
import world.willfrog.agent.tool.PythonSandboxTools;
import world.willfrog.agent.tool.ToolRouter;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

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
    private ToolRouter toolRouter;
    @Mock
    private ParallelGraphExecutor parallelGraphExecutor;
    @Mock
    private AgentRunStateStore stateStore;
    @Mock
    private AgentPromptService promptService;
    @Mock
    private AgentObservabilityService observabilityService;
    @Mock
    private AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;

    @Mock
    private ChatLanguageModel chatLanguageModel;

    private AgentRunExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new AgentRunExecutor(
                runMapper,
                eventService,
                aiServiceFactory,
                marketDataTools,
                pythonSandboxTools,
                toolRouter,
                parallelGraphExecutor,
                stateStore,
                promptService,
                observabilityService,
                llmRequestSnapshotBuilder,
                new ObjectMapper()
        );

        when(eventService.extractEndpointName(anyString())).thenReturn("");
        when(eventService.extractModelName(anyString())).thenReturn("");
        when(eventService.extractCaptureLlmRequests(anyString())).thenReturn(false);
        when(eventService.extractDebugMode(anyString())).thenReturn(false);
        when(eventService.extractOpenRouterProviderOrder(anyString())).thenReturn(List.of());
        when(eventService.extractUserGoal(anyString())).thenReturn("goal");

        when(aiServiceFactory.resolveLlm(anyString(), anyString()))
                .thenReturn(new AgentLlmResolver.ResolvedLlm("ep", "base", "model", ""));
        when(aiServiceFactory.buildChatModelWithProviderOrder(any(), any())).thenReturn(chatLanguageModel);
        lenient().when(observabilityService.attachObservabilityToSnapshot(anyString(), any(), any())).thenReturn("{}");
    }

    @Test
    void execute_whenDagEnabled_shouldNotFallbackToLegacyLoop() {
        AgentRun run = run("run-dag");
        when(runMapper.findById("run-dag")).thenReturn(run);
        when(eventService.isRunnable("run-dag", "u1")).thenReturn(true);
        when(parallelGraphExecutor.isEnabled()).thenReturn(true);
        when(parallelGraphExecutor.execute(any(), anyString(), anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        executor.execute("run-dag");

        verify(parallelGraphExecutor).execute(any(), anyString(), anyString(), any(), any(), anyString(), anyString(), anyString());
        verify(eventService, never()).append(eq("run-dag"), eq("u1"), eq("PARALLEL_FALLBACK_TO_SERIAL"), anyMap());
        verify(chatLanguageModel, never()).generate(any(List.class), any(List.class));
    }

    @Test
    void execute_whenDagDisabled_shouldUseLegacyLoop() {
        AgentRun run = run("run-legacy");
        when(runMapper.findById("run-legacy")).thenReturn(run);
        when(eventService.isRunnable("run-legacy", "u1")).thenReturn(true, true);
        when(parallelGraphExecutor.isEnabled()).thenReturn(false);
        when(promptService.agentRunSystemPrompt()).thenReturn("sys");
        when(llmRequestSnapshotBuilder.buildChatCompletionsRequest(anyString(), anyString(), anyString(), any(), any(), anyMap()))
                .thenReturn(Map.of());

        @SuppressWarnings("unchecked")
        Response<AiMessage> response = mock(Response.class);
        AiMessage aiMessage = mock(AiMessage.class);
        when(aiMessage.hasToolExecutionRequests()).thenReturn(false);
        when(aiMessage.text()).thenReturn("done");
        when(response.content()).thenReturn(aiMessage);
        when(response.tokenUsage()).thenReturn(null);
        when(chatLanguageModel.generate(any(List.class), any(List.class))).thenReturn(response);

        executor.execute("run-legacy");

        verify(eventService).append(eq("run-legacy"), eq("u1"), eq("PARALLEL_FALLBACK_TO_SERIAL"), anyMap());
        verify(runMapper).updateSnapshot(eq("run-legacy"), eq("u1"), eq(AgentRunStatus.COMPLETED), any(), eq(true), eq(null));
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

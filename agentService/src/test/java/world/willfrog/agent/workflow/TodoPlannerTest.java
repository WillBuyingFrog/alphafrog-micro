package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
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
    private ChatModel model;
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
        // ReAct 两步规划所需的 prompt 方法
        lenient().when(promptService.reactSystemPrompt()).thenReturn("unified-system");
        lenient().when(promptService.planningAnalysisStageInstruction(anyString(), any(Integer.class))).thenReturn("[Stage: PLANNING_ANALYSIS]");
        lenient().when(promptService.planningStructuredStageInstruction()).thenReturn("[Stage: PLANNING_STRUCTURED]");
        lenient().when(promptService.dynamicContextPrefix()).thenReturn("今天是2026年03月08日。");
        lenient().when(stateStore.isPlanOverride(anyString())).thenReturn(false);
        lenient().when(stateStore.loadPlan(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void plan_shouldGenerateTodoListAndPersist() {
        AgentRun run = run("run-1");

        // Step 1: 自然语言分析 response
        AiMessage analysisMsg = mock(AiMessage.class);
        when(analysisMsg.text()).thenReturn("经过分析，用户需要搜索平安相关的股票信息。");
        ChatResponse analysisResp = ChatResponse.builder()
                .aiMessage(analysisMsg)
                .metadata(ChatResponseMetadata.builder().build())
                .build();

        // Step 2: 结构化 JSON response
        AiMessage structuredMsg = mock(AiMessage.class);
        when(structuredMsg.text()).thenReturn(
            "{\"analysis\":\"分析用户查询需求\",\"items\":[" +
            "{\"id\":\"todo_1\",\"sequence\":1,\"type\":\"TOOL_CALL\",\"toolName\":\"searchStock\",\"params\":{\"keyword\":\"平安\"},\"reasoning\":\"需要搜索股票信息\",\"executionMode\":\"AUTO\"}" +
            "]}"
        );
        ChatResponse structuredResp = ChatResponse.builder()
                .aiMessage(structuredMsg)
                .metadata(ChatResponseMetadata.builder().build())
                .build();

        // 两步规划：第一次返回分析，第二次返回结构化 JSON
        when(model.chat(any(List.class)))
                .thenReturn(analysisResp)
                .thenReturn(structuredResp);

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
        // 验证 analysis 使用了自然语言分析的结果
        assertEquals("经过分析，用户需要搜索平安相关的股票信息。", plan.getAnalysis());
        verify(runMapper).updatePlan(eq("run-1"), eq("u1"), eq(AgentRunStatus.EXECUTING), anyString());
        verify(eventService).append(eq("run-1"), eq("u1"), eq("TODO_LIST_CREATED"), anyMap());
    }

    @Test
    void plan_shouldRespectMaxTodos() {
        AgentRun run = run("run-2");

        // Step 1: analysis
        AiMessage analysisMsg = mock(AiMessage.class);
        when(analysisMsg.text()).thenReturn("需要查询多个股票信息。");
        ChatResponse analysisResp = ChatResponse.builder()
                .aiMessage(analysisMsg)
                .metadata(ChatResponseMetadata.builder().build())
                .build();

        // Step 2: structured
        AiMessage structuredMsg = mock(AiMessage.class);
        when(structuredMsg.text()).thenReturn(
            "{\"analysis\":\"需要查询多个股票\",\"items\":[" +
            "{\"id\":\"1\",\"sequence\":1,\"type\":\"TOOL_CALL\",\"toolName\":\"searchStock\",\"params\":{},\"reasoning\":\"搜索第一个股票\",\"executionMode\":\"AUTO\"},"
+
            "{\"id\":\"2\",\"sequence\":2,\"type\":\"TOOL_CALL\",\"toolName\":\"searchStock\",\"params\":{},\"reasoning\":\"搜索第二个股票\",\"executionMode\":\"AUTO\"}" +
            "]}"
        );
        ChatResponse structuredResp = ChatResponse.builder()
                .aiMessage(structuredMsg)
                .metadata(ChatResponseMetadata.builder().build())
                .build();

        when(model.chat(any(List.class)))
                .thenReturn(analysisResp)
                .thenReturn(structuredResp);

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

        // Step 1: analysis
        AiMessage analysisMsg = mock(AiMessage.class);
        when(analysisMsg.text()).thenReturn("尝试使用未知工具。");
        ChatResponse analysisResp = ChatResponse.builder()
                .aiMessage(analysisMsg)
                .metadata(ChatResponseMetadata.builder().build())
                .build();

        // Step 2: structured with illegal tool
        AiMessage structuredMsg = mock(AiMessage.class);
        when(structuredMsg.text()).thenReturn(
            "{\"analysis\":\"测试非法工具\",\"items\":[" +
            "{\"id\":\"todo_1\",\"sequence\":1,\"type\":\"TOOL_CALL\",\"toolName\":\"unknownTool\",\"params\":{},\"reasoning\":\"尝试使用非法工具\",\"executionMode\":\"AUTO\"}" +
            "]}"
        );
        ChatResponse structuredResp = ChatResponse.builder()
                .aiMessage(structuredMsg)
                .metadata(ChatResponseMetadata.builder().build())
                .build();

        when(model.chat(any(List.class)))
                .thenReturn(analysisResp)
                .thenReturn(structuredResp);

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

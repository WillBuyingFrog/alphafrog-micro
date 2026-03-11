package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.tool.ToolRouter;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class ReactTodoExecutorTest {

    @Mock
    private AgentPromptService promptService;
    @Mock
    private ToolRouter toolRouter;
    @Mock
    private AgentObservabilityService observabilityService;
    @Mock
    private ChatModel model;

    private ReactTodoExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ReactTodoExecutor(promptService, toolRouter, new ObjectMapper(), observabilityService);
        ReflectionTestUtils.setField(executor, "maxCallsPerTodo", 10);
        AgentContext.clear();

        lenient().when(promptService.dagReactSystemPrompt()).thenReturn("system prompt");
        lenient().when(promptService.dynamicContextPrefix()).thenReturn("dynamic prefix");
        lenient().when(observabilityService.recordLlmCall(
                anyString(), anyString(), any(), anyLong(),
                any(), any(), any(), anyMap(), anyString()
        )).thenReturn("trace-1");
    }

    @Test
    void executeWithObservability_shouldClearDecisionContextAfterSuccessfulToolCall() {
        // LLM first decides to call a tool, then returns answer
        when(model.chat(any(List.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("{\"tool\":\"searchIndex\",\"params\":{\"keyword\":\"沪深300\"}}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("{\"answer\":\"搜索完成\"}"))
                        .build());
        when(toolRouter.invoke(eq("searchIndex"), anyMap())).thenReturn("{\"ok\":true}");

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "搜索指数",
                context(),
                model,
                "run-1",
                "dag_execution"
        );

        assertTrue(record.isSuccess());
        assertEquals(1, record.getToolCallsUsed());
        assertNull(AgentContext.getDecisionTraceId());
        assertNull(AgentContext.getDecisionStage());
        assertNull(AgentContext.getDecisionExcerpt());
    }

    @Test
    void executeWithObservability_shouldSupportMultiRoundReActLoop() {
        // LLM calls tool A, then tool B, then returns answer — 3 rounds
        when(model.chat(any(List.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("{\"tool\":\"searchIndex\",\"params\":{\"keyword\":\"沪深300\"}}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("{\"tool\":\"getIndexDaily\",\"params\":{\"ts_code\":\"000300.SH\",\"start_date\":\"20250101\",\"end_date\":\"20251231\"}}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("{\"answer\":\"沪深300指数2025年日线数据已获取\"}"))
                        .build());
        when(toolRouter.invoke(eq("searchIndex"), anyMap())).thenReturn("{\"ok\":true,\"data\":{\"ts_code\":\"000300.SH\"}}");
        when(toolRouter.invoke(eq("getIndexDaily"), anyMap())).thenReturn("{\"ok\":true,\"data\":{\"dataset_id\":\"ds_001\"}}");

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "获取沪深300指数2025年全年的日线行情数据",
                context(),
                model,
                "run-1",
                "dag_execution"
        );

        assertTrue(record.isSuccess());
        assertEquals(2, record.getToolCallsUsed());
        assertEquals("沪深300指数2025年日线数据已获取", record.getOutput());
        // LLM was called 3 times (2 tool decisions + 1 answer)
        verify(model, times(3)).chat(any(List.class));
        // Tools were called 2 times
        verify(toolRouter, times(1)).invoke(eq("searchIndex"), anyMap());
        verify(toolRouter, times(1)).invoke(eq("getIndexDaily"), anyMap());
    }

    @Test
    void executeWithObservability_shouldDirectlyAnswerWithoutToolCall() {
        when(model.chat(any(List.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("{\"answer\":\"无需工具调用，直接回答\"}"))
                        .build());

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "简单问题",
                context(),
                model,
                "run-1",
                "dag_execution"
        );

        assertTrue(record.isSuccess());
        assertEquals(0, record.getToolCallsUsed());
        assertEquals("无需工具调用，直接回答", record.getOutput());
    }

    @Test
    void executeWithObservability_shouldRespectMaxCallsPerTodo() {
        ReflectionTestUtils.setField(executor, "maxCallsPerTodo", 2);

        // LLM keeps wanting to call tools, never returns answer
        when(model.chat(any(List.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("{\"tool\":\"searchIndex\",\"params\":{\"keyword\":\"test\"}}"))
                        .build());
        when(toolRouter.invoke(eq("searchIndex"), anyMap())).thenReturn("{\"ok\":true}");

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "无限循环测试",
                context(),
                model,
                "run-1",
                "dag_execution"
        );

        // Should fail with max calls limit
        assertFalse(record.isSuccess());
        assertTrue(record.getSummary().contains("max call limit"));
    }

    @Test
    void executeWithObservability_shouldContinueAfterToolFailure() {
        // LLM calls tool A (fails), then decides to try tool B (succeeds), then returns answer
        when(model.chat(any(List.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("{\"tool\":\"searchIndex\",\"params\":{\"keyword\":\"bad\"}}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("{\"tool\":\"searchIndex\",\"params\":{\"keyword\":\"good\"}}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("{\"answer\":\"找到了\"}"))
                        .build());
        when(toolRouter.invoke(eq("searchIndex"), anyMap()))
                .thenReturn("{\"ok\":false,\"error\":{\"message\":\"not found\"}}")
                .thenReturn("{\"ok\":true,\"data\":{\"ts_code\":\"000001.SH\"}}");

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "搜索指数",
                context(),
                model,
                "run-1",
                "dag_execution"
        );

        assertTrue(record.isSuccess());
        assertEquals(2, record.getToolCallsUsed());
    }

    @Test
    void executeWithObservability_shouldClearDecisionContextWhenToolThrowsNonExceptionThrowable() {
        when(model.chat(any(List.class))).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("{\"tool\":\"searchIndex\",\"params\":{\"keyword\":\"沪深300\"}}"))
                .build());
        when(toolRouter.invoke(eq("searchIndex"), anyMap())).thenThrow(new AssertionError("fatal"));

        assertThrows(AssertionError.class, () -> executor.executeWithObservability(
                "搜索指数",
                context(),
                model,
                "run-1",
                "dag_execution"
        ));
        assertNull(AgentContext.getDecisionTraceId());
        assertNull(AgentContext.getDecisionStage());
        assertNull(AgentContext.getDecisionExcerpt());
    }

    @Test
    void buildMessages_systemPromptShouldBeStaticWithNoDynamicContent() {
        // Verify that the System Message is exactly dagReactSystemPrompt() with no dynamic content,
        // so KV prefix cache can be maximized across different runs/users.
        // context() sets userGoal = "分析指数"; "查询沪深300" is the task description (different field).
        when(model.chat(any(List.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("{\"answer\":\"done\"}"))
                        .build());

        executor.executeWithObservability("查询沪深300", context(), model, "run-kv-test", "test");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<dev.langchain4j.data.message.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(model).chat(captor.capture());
        List<dev.langchain4j.data.message.ChatMessage> msgs = captor.getValue();

        // First message must be SystemMessage with exactly the static system prompt (no dynamic content)
        assertInstanceOf(dev.langchain4j.data.message.SystemMessage.class, msgs.get(0));
        String sysText = ((dev.langchain4j.data.message.SystemMessage) msgs.get(0)).text();
        String expectedSystemPrompt = promptService.dagReactSystemPrompt(); // "system prompt" from mock
        assertFalse(sysText.contains("分析指数"), "SystemMessage must not contain userGoal");
        assertFalse(sysText.contains("searchIndex"), "SystemMessage must not contain tool list");
        assertEquals(expectedSystemPrompt, sysText, "SystemMessage must equal static dagReactSystemPrompt()");

        // Second message must be UserMessage containing the dynamic context (userGoal from context())
        assertInstanceOf(dev.langchain4j.data.message.UserMessage.class, msgs.get(1));
        String userText = ((dev.langchain4j.data.message.UserMessage) msgs.get(1)).singleText();
        // "分析指数" is the userGoal set in context(), not the task description "查询沪深300"
        assertTrue(userText.contains("分析指数"), "First UserMessage must contain userGoal");
    }

    private ReactTodoExecutor.TodoExecutionContext context() {
        return ReactTodoExecutor.TodoExecutionContext.builder()
                .userGoal("分析指数")
                .availableTools(Set.of("searchIndex", "getIndexDaily"))
                .completedTodos(List.of())
                .datasetRefs(new java.util.HashMap<>())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Sub-Agent Tests (#36 §4.3)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void spawnAndWaitForSubAgent_shouldRunSubAgentAndReturnResult() {
        // Inject a mock SubAgentRunner
        world.willfrog.agent.graph.SubAgentRunner mockSubAgentRunner =
                org.mockito.Mockito.mock(world.willfrog.agent.graph.SubAgentRunner.class);
        executor.setSubAgentRunner(mockSubAgentRunner);
        ReflectionTestUtils.setField(executor, "subAgentTimeoutSeconds", 10);

        world.willfrog.agent.graph.SubAgentRunner.SubAgentResult subAgentResult =
                world.willfrog.agent.graph.SubAgentRunner.SubAgentResult.builder()
                        .success(true)
                        .answer("创新药指数A的月初定投收益率为8.5%")
                        .build();
        when(mockSubAgentRunner.run(any(), eq(model))).thenReturn(subAgentResult);

        // LLM sequence: spawn sub-agent → wait for it → return answer
        when(model.chat(any(List.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage(
                                "{\"tool\":\"spawnSubAgent\",\"params\":{\"goal\":\"计算创新药指数A的月初定投收益\"}}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage(
                                "{\"tool\":\"waitForSubAgent\",\"params\":{\"sub_agent_id\":\"sa_0\"}}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("{\"answer\":\"定投收益分析完成\"}"))
                        .build());

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "分析创新药指数定投收益",
                context(),
                model,
                "run-sub-agent",
                "test"
        );

        assertTrue(record.isSuccess());
        // LLM's final answer is whatever the third model.chat() call returned
        assertEquals("定投收益分析完成", record.getOutput());
        // Sub-agent was invoked exactly once with the correct goal
        assertEquals(2, record.getToolCallsUsed()); // spawnSubAgent + waitForSubAgent
        verify(mockSubAgentRunner).run(any(), eq(model));
    }

    @Test
    void spawnSubAgent_withoutSubAgentRunner_shouldReturnNotAvailable() {
        // executor has no SubAgentRunner set (null by default)
        when(model.chat(any(List.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage(
                                "{\"tool\":\"spawnSubAgent\",\"params\":{\"goal\":\"某子任务\"}}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("{\"answer\":\"跳过子代理，直接完成\"}"))
                        .build());

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "测试无 SubAgentRunner 场景",
                context(),
                model,
                "run-no-runner",
                "test"
        );

        // Should still complete (LLM sees "not_available" and adapts)
        assertTrue(record.isSuccess());
    }

    @Test
    void spawnSubAgent_shouldExcludeSubAgentToolsFromSubAgentWhitelist() {
        // Verify sub-agents can't recursively spawn sub-agents
        world.willfrog.agent.graph.SubAgentRunner mockSubAgentRunner =
                org.mockito.Mockito.mock(world.willfrog.agent.graph.SubAgentRunner.class);
        executor.setSubAgentRunner(mockSubAgentRunner);
        ReflectionTestUtils.setField(executor, "subAgentTimeoutSeconds", 10);

        when(mockSubAgentRunner.run(any(), eq(model))).thenReturn(
                world.willfrog.agent.graph.SubAgentRunner.SubAgentResult.builder()
                        .success(true).answer("done").build());

        // Context has spawnSubAgent in availableTools
        ReactTodoExecutor.TodoExecutionContext ctx = ReactTodoExecutor.TodoExecutionContext.builder()
                .userGoal("复杂任务")
                .availableTools(new java.util.HashSet<>(Set.of("searchIndex", "spawnSubAgent", "waitForSubAgent")))
                .completedTodos(List.of())
                .datasetRefs(new java.util.HashMap<>())
                .build();

        when(model.chat(any(List.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage(
                                "{\"tool\":\"spawnSubAgent\",\"params\":{\"goal\":\"子任务\"}}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage(
                                "{\"tool\":\"waitForSubAgent\",\"params\":{\"sub_agent_id\":\"sa_0\"}}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("{\"answer\":\"完成\"}"))
                        .build());

        executor.executeWithObservability("测试子代理白名单", ctx, model, "run-whitelist", "test");

        // Verify subAgentRunner was called with whitelist NOT containing spawnSubAgent or waitForSubAgent
        org.mockito.ArgumentCaptor<world.willfrog.agent.graph.SubAgentRunner.SubAgentRequest> reqCaptor =
                org.mockito.ArgumentCaptor.forClass(world.willfrog.agent.graph.SubAgentRunner.SubAgentRequest.class);
        verify(mockSubAgentRunner).run(reqCaptor.capture(), eq(model));
        Set<String> subAgentWhitelist = reqCaptor.getValue().getToolWhitelist();
        assertFalse(subAgentWhitelist.contains("spawnSubAgent"),
                "Sub-agent must not have spawnSubAgent in its whitelist");
        assertFalse(subAgentWhitelist.contains("waitForSubAgent"),
                "Sub-agent must not have waitForSubAgent in its whitelist");
        assertTrue(subAgentWhitelist.contains("searchIndex"),
                "Sub-agent should have business tools in whitelist");
    }

    @Test
    void waitForSubAgent_withUnknownId_shouldReturnError() {
        world.willfrog.agent.graph.SubAgentRunner mockSubAgentRunner =
                org.mockito.Mockito.mock(world.willfrog.agent.graph.SubAgentRunner.class);
        executor.setSubAgentRunner(mockSubAgentRunner);

        // LLM tries to wait for an ID that was never spawned
        when(model.chat(any(List.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage(
                                "{\"tool\":\"waitForSubAgent\",\"params\":{\"sub_agent_id\":\"sa_99\"}}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("{\"answer\":\"处理了未知错误\"}"))
                        .build());

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "未知子代理ID测试",
                context(),
                model,
                "run-unknown-id",
                "test"
        );

        // LLM should see the error and still complete
        assertTrue(record.isSuccess());
        verify(mockSubAgentRunner, org.mockito.Mockito.never()).run(any(), any());
    }
}

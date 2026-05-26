package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.tools.router.ToolRouter;

import java.time.Duration;
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
    private AgentRunStateStore stateStore;
    @Mock
    private ChatModel model;

    private ReactTodoExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ReactTodoExecutor(promptService, toolRouter, new ObjectMapper(), observabilityService, stateStore);
        ReflectionTestUtils.setField(executor, "maxCallsPerTodo", 10);
        AgentContext.clear();

        lenient().when(promptService.dagReactSystemPrompt()).thenReturn("system prompt");
        lenient().when(promptService.dynamicContextPrefix()).thenReturn("dynamic prefix");
        lenient().when(promptService.maxSubAgentCount()).thenReturn(3);
        lenient().when(promptService.subAgentEndpointName()).thenReturn("openrouter");
        lenient().when(promptService.selectSubAgentModelName(anyString(), anyString())).thenReturn("openai/gpt-5.2");
        lenient().when(observabilityService.recordLlmCall(
                anyString(), anyString(), any(), anyLong(),
                any(), any(), any(), anyMap(), anyString()
        )).thenReturn("trace-1");
    }

    @Test
    void executeWithObservability_shouldClearDecisionContextAfterSuccessfulToolCall() {
        ReactTodoExecutor.TodoExecutionContext ctx = contextWithMarketSpecs();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_1", "searchIndex", "{\"keyword\":\"沪深300\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("搜索完成"))
                        .build());
        when(toolRouter.invokeWithMeta(eq("searchIndex"), anyMap()))
                .thenReturn(invocationResult("{\"ok\":true}", true));

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "搜索指数",
                ctx,
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
        ReactTodoExecutor.TodoExecutionContext ctx = contextWithMarketSpecs();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_1", "searchIndex", "{\"keyword\":\"沪深300\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_2", "getIndexDaily",
                                "{\"ts_code\":\"000300.SH\",\"start_date\":\"20250101\",\"end_date\":\"20251231\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("沪深300指数2025年日线数据已获取"))
                        .build());
        when(toolRouter.invokeWithMeta(eq("searchIndex"), anyMap()))
                .thenReturn(invocationResult("{\"ok\":true,\"data\":{\"ts_code\":\"000300.SH\"}}", true));
        when(toolRouter.invokeWithMeta(eq("getIndexDaily"), anyMap()))
                .thenReturn(invocationResult("{\"ok\":true,\"data\":{\"dataset_id\":\"ds_001\"}}", true));

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "获取沪深300指数2025年全年的日线行情数据",
                ctx,
                model,
                "run-1",
                "dag_execution"
        );

        assertTrue(record.isSuccess());
        assertEquals(2, record.getToolCallsUsed());
        assertEquals("沪深300指数2025年日线数据已获取", record.getOutput());
        // LLM was called 3 times (2 tool decisions + 1 answer)
        verify(model, times(3)).chat(any(ChatRequest.class));
        // Tools were called 2 times
        verify(toolRouter, times(1)).invokeWithMeta(eq("searchIndex"), anyMap());
        verify(toolRouter, times(1)).invokeWithMeta(eq("getIndexDaily"), anyMap());
    }

    @Test
    void executeWithObservability_shouldUseNativeToolExecutionRequestsWhenToolSpecsProvided() {
        ReactTodoExecutor.TodoExecutionContext nativeContext = ReactTodoExecutor.TodoExecutionContext.builder()
                .userGoal("分析指数")
                .availableTools(Set.of("searchIndex"))
                .toolSpecifications(List.of(
                        ToolSpecification.builder()
                                .name("searchIndex")
                                .description("搜索指数")
                                .parameters(JsonObjectSchema.builder()
                                        .addStringProperty("keyword")
                                        .required("keyword")
                                        .additionalProperties(false)
                                        .build())
                                .build()))
                .completedTodos(List.of())
                .datasetRefs(new java.util.HashMap<>())
                .build();

        AiMessage toolCallMessage = AiMessage.from(
                "",
                List.of(ToolExecutionRequest.builder()
                        .id("call_1")
                        .name("searchIndex")
                        .arguments("{\"keyword\":\"沪深300\"}")
                        .build())
        );
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(toolCallMessage).build())
                .thenReturn(ChatResponse.builder().aiMessage(new AiMessage("最终回答")).build());
        when(toolRouter.invokeWithMeta(eq("searchIndex"), anyMap()))
                .thenReturn(invocationResult("{\"ok\":true,\"data\":{\"ts_code\":\"000300.SH\"}}", true));

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "搜索指数",
                nativeContext,
                model,
                "run-native-1",
                "dag_execution"
        );

        assertTrue(record.isSuccess());
        assertEquals(1, record.getToolCallsUsed());
        assertEquals("最终回答", record.getOutput());
        verify(model, times(2)).chat(any(ChatRequest.class));
        verify(toolRouter).invokeWithMeta(eq("searchIndex"), anyMap());
    }

    @Test
    void executeWithObservability_shouldNotExecuteTextJsonToolCallFallback() {
        ReactTodoExecutor.TodoExecutionContext nativeContext = ReactTodoExecutor.TodoExecutionContext.builder()
                .userGoal("请必须调用 searchWeb 工具一次")
                .availableTools(Set.of("searchWeb"))
                .toolSpecifications(List.of(searchWebSpec()))
                .completedTodos(List.of())
                .datasetRefs(new java.util.HashMap<>())
                .build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("""
                                ```json
                                {"tool_name":"searchWeb","parameters":{"query":"今天A股市场有哪些重要新闻和政策变化","scene":"finance"}}
                                ```
                                """))
                        .build());

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "调用 searchWeb 工具查询今天A股市场的重要新闻和政策变化",
                nativeContext,
                model,
                "run-web-text",
                "dag_execution"
        );

        assertTrue(record.isSuccess());
        assertEquals(0, record.getToolCallsUsed());
        assertTrue(record.getOutput().contains("\"tool_name\":\"searchWeb\""));
        verify(toolRouter, org.mockito.Mockito.never()).invokeWithMeta(anyString(), anyMap());
    }

    @Test
    void executeWithObservability_shouldDirectlyAnswerWithoutToolCall() {
        when(model.chat(any(List.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("无需工具调用，直接回答"))
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
        ReactTodoExecutor.TodoExecutionContext ctx = contextWithMarketSpecs();

        // LLM keeps wanting to call tools, never returns answer
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_repeat", "searchIndex", "{\"keyword\":\"test\"}"))
                        .build());
        when(toolRouter.invokeWithMeta(eq("searchIndex"), anyMap()))
                .thenReturn(invocationResult("{\"ok\":true}", true));

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "无限循环测试",
                ctx,
                model,
                "run-1",
                "dag_execution"
        );

        // Should fail with max calls limit
        assertFalse(record.isSuccess());
        assertTrue(record.getSummary().contains("max call limit"));
    }

    @Test
    void executeWithObservability_shouldStopWhenSameFailedToolCallRepeats() {
        ReflectionTestUtils.setField(executor, "maxCallsPerTodo", 10);
        ReflectionTestUtils.setField(executor, "maxSameFailedToolCallPerTodo", 1);
        ReactTodoExecutor.TodoExecutionContext ctx = contextWithMarketSpecs();

        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_1", "searchIndex", "{\"keyword\":\"bad\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_2", "searchIndex", "{\"keyword\":\"bad\"}"))
                        .build());
        when(toolRouter.invokeWithMeta(eq("searchIndex"), anyMap()))
                .thenReturn(invocationResult("{\"ok\":false,\"error\":{\"code\":\"NO_DATA\",\"message\":\"not found\"}}", false));

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "重复失败工具调用",
                ctx,
                model,
                "run-repeat",
                "dag_execution"
        );

        assertFalse(record.isSuccess());
        assertTrue(record.getSummary().startsWith("repeated_tool_call:searchIndex:"));
        assertEquals(2, record.getToolCallsUsed());
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void executeWithObservability_shouldContinueAfterToolFailure() {
        ReactTodoExecutor.TodoExecutionContext ctx = contextWithMarketSpecs();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_1", "searchIndex", "{\"keyword\":\"bad\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_2", "searchIndex", "{\"keyword\":\"good\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("找到了"))
                        .build());
        when(toolRouter.invokeWithMeta(eq("searchIndex"), anyMap()))
                .thenReturn(invocationResult("{\"ok\":false,\"error\":{\"message\":\"not found\"}}", false))
                .thenReturn(invocationResult("{\"ok\":true,\"data\":{\"ts_code\":\"000001.SH\"}}", true));

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "搜索指数",
                ctx,
                model,
                "run-1",
                "dag_execution"
        );

        assertTrue(record.isSuccess());
        assertEquals(2, record.getToolCallsUsed());
    }

    @Test
    void executeWithObservability_shouldClearDecisionContextWhenToolThrowsNonExceptionThrowable() {
        ReactTodoExecutor.TodoExecutionContext ctx = contextWithMarketSpecs();
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(toolCallMessage("call_1", "searchIndex", "{\"keyword\":\"沪深300\"}"))
                .build());
        when(toolRouter.invokeWithMeta(eq("searchIndex"), anyMap())).thenThrow(new AssertionError("fatal"));

        assertThrows(AssertionError.class, () -> executor.executeWithObservability(
                "搜索指数",
                ctx,
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

    @Test
    void buildMessages_shouldUseCompletedTodoSummaryByDefault() {
        CompletedTodoInfo completed = CompletedTodoInfo.builder()
                .todoId("todo_1")
                .description("已完成任务")
                .summary("短摘要")
                .output("{\"ok\":true,\"data\":{\"dataset_id\":\"ds_001\"}}")
                .messageHistory(List.of(
                        CompletedTodoInfo.ChatMessageSnapshot.builder()
                                .role("assistant")
                                .content("完整历史不应默认注入")
                                .build()))
                .build();
        ReactTodoExecutor.TodoExecutionContext ctx = ReactTodoExecutor.TodoExecutionContext.builder()
                .userGoal("分析指数")
                .availableTools(Set.of("searchIndex"))
                .completedTodos(List.of(completed))
                .datasetRefs(new java.util.HashMap<>())
                .build();
        when(model.chat(any(List.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("完成"))
                        .build());

        executor.executeWithObservability("下一步", ctx, model, "run-summary", "test");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<dev.langchain4j.data.message.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(model).chat(captor.capture());
        String joined = captor.getValue().stream()
                .filter(msg -> msg instanceof dev.langchain4j.data.message.UserMessage)
                .map(msg -> ((dev.langchain4j.data.message.UserMessage) msg).singleText())
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(joined.contains("短摘要"));
        assertTrue(joined.contains("ds_001"));
        assertFalse(joined.contains("完整历史不应默认注入"));
    }

    private ReactTodoExecutor.TodoExecutionContext context() {
        return ReactTodoExecutor.TodoExecutionContext.builder()
                .userGoal("分析指数")
                .availableTools(Set.of("searchIndex", "getIndexDaily"))
                .completedTodos(List.of())
                .datasetRefs(new java.util.HashMap<>())
                .build();
    }

    private ReactTodoExecutor.TodoExecutionContext contextWithMarketSpecs() {
        return ReactTodoExecutor.TodoExecutionContext.builder()
                .userGoal("分析指数")
                .availableTools(Set.of("searchIndex", "getIndexDaily"))
                .toolSpecifications(List.of(searchIndexSpec(), getIndexDailySpec()))
                .completedTodos(List.of())
                .datasetRefs(new java.util.HashMap<>())
                .build();
    }

    private ReactTodoExecutor.TodoExecutionContext contextWithSubAgentTools() {
        return ReactTodoExecutor.TodoExecutionContext.builder()
                .userGoal("复杂任务")
                .availableTools(Set.of("spawnSubAgent", "waitForSubAgent"))
                .completedTodos(List.of())
                .datasetRefs(new java.util.HashMap<>())
                .build();
    }

    private AiMessage toolCallMessage(String id, String name, String arguments) {
        return AiMessage.from(
                "",
                List.of(ToolExecutionRequest.builder()
                        .id(id)
                        .name(name)
                        .arguments(arguments)
                        .build())
        );
    }

    private ToolRouter.ToolInvocationResult invocationResult(String output, boolean success) {
        return ToolRouter.ToolInvocationResult.builder()
                .output(output)
                .success(success)
                .durationMs(10L)
                .cacheMeta(null)
                .build();
    }

    private ToolSpecification searchIndexSpec() {
        return ToolSpecification.builder()
                .name("searchIndex")
                .description("搜索指数")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("keyword")
                        .required("keyword")
                        .additionalProperties(false)
                        .build())
                .build();
    }

    private ToolSpecification getIndexDailySpec() {
        return ToolSpecification.builder()
                .name("getIndexDaily")
                .description("查询指数日线")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("ts_code")
                        .addStringProperty("start_date")
                        .addStringProperty("end_date")
                        .required("ts_code", "start_date", "end_date")
                        .additionalProperties(false)
                        .build())
                .build();
    }

    private ToolSpecification executePythonSpec() {
        return ToolSpecification.builder()
                .name("executePython")
                .description("执行 Python 代码")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("code")
                        .addStringProperty("dataset_ids")
                        .addStringProperty("libraries")
                        .required("code", "dataset_ids")
                        .additionalProperties(false)
                        .build())
                .build();
    }

    private ReactTodoExecutor.TodoExecutionContext pythonComputationContext(java.util.Map<String, String> datasets) {
        return ReactTodoExecutor.TodoExecutionContext.builder()
                .userGoal("行业回测")
                .availableTools(Set.of("executePython"))
                .toolSpecifications(List.of(executePythonSpec()))
                .completedTodos(List.of())
                .datasetRefs(datasets)
                .build();
    }

    private ToolSpecification searchWebSpec() {
        return ToolSpecification.builder()
                .name("searchWeb")
                .description("联网搜索")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query")
                        .addStringProperty("scene")
                        .required("query")
                        .additionalProperties(false)
                        .build())
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

        ReactTodoExecutor.TodoExecutionContext ctx = contextWithSubAgentTools();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_spawn", "spawnSubAgent",
                                "{\"goal\":\"计算创新药指数A的月初定投收益\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_wait", "waitForSubAgent", "{\"sub_agent_id\":\"sa_0\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("定投收益分析完成"))
                        .build());

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "分析创新药指数定投收益",
                ctx,
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
        ReactTodoExecutor.TodoExecutionContext ctx = contextWithSubAgentTools();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_spawn", "spawnSubAgent", "{\"goal\":\"某子任务\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("跳过子代理，直接完成"))
                        .build());

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "测试无 SubAgentRunner 场景",
                ctx,
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

        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_spawn", "spawnSubAgent", "{\"goal\":\"子任务\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_wait", "waitForSubAgent", "{\"sub_agent_id\":\"sa_0\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("完成"))
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
        ReactTodoExecutor.TodoExecutionContext ctx = contextWithSubAgentTools();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_wait", "waitForSubAgent", "{\"sub_agent_id\":\"sa_99\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("处理了未知错误"))
                        .build());

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "未知子代理ID测试",
                ctx,
                model,
                "run-unknown-id",
                "test"
        );

        // LLM should see the error and still complete
        assertTrue(record.isSuccess());
        verify(mockSubAgentRunner, org.mockito.Mockito.never()).run(any(), any());
    }

    @Test
    void spawnSubAgent_shouldRespectMaxCountLimit() {
        world.willfrog.agent.graph.SubAgentRunner mockSubAgentRunner =
                org.mockito.Mockito.mock(world.willfrog.agent.graph.SubAgentRunner.class);
        executor.setSubAgentRunner(mockSubAgentRunner);
        when(promptService.maxSubAgentCount()).thenReturn(1);

        when(mockSubAgentRunner.run(any(), eq(model))).thenAnswer(invocation -> {
            Thread.sleep(50);
            return world.willfrog.agent.graph.SubAgentRunner.SubAgentResult.builder()
                    .success(true).answer("ok").build();
        });

        ReactTodoExecutor.TodoExecutionContext ctx = contextWithSubAgentTools();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_spawn_1", "spawnSubAgent", "{\"goal\":\"子任务1\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_spawn_2", "spawnSubAgent", "{\"goal\":\"子任务2\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("done"))
                        .build());

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "测试并发上限",
                ctx,
                model,
                "run-max-count",
                "test"
        );

        assertTrue(record.isSuccess());
        verify(mockSubAgentRunner, times(1)).run(any(), eq(model));
    }

    @Test
    void spawnSubAgent_twiceShouldUseMonotonicIds() {
        world.willfrog.agent.graph.SubAgentRunner mockSubAgentRunner =
                org.mockito.Mockito.mock(world.willfrog.agent.graph.SubAgentRunner.class);
        executor.setSubAgentRunner(mockSubAgentRunner);
        when(mockSubAgentRunner.run(any(), eq(model))).thenReturn(
                world.willfrog.agent.graph.SubAgentRunner.SubAgentResult.builder()
                        .success(true).answer("ok").build());

        ReactTodoExecutor.TodoExecutionContext ctx = contextWithSubAgentTools();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_spawn_1", "spawnSubAgent", "{\"goal\":\"子任务A\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_spawn_2", "spawnSubAgent", "{\"goal\":\"子任务B\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("done"))
                        .build());

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "测试子代理ID递增",
                ctx,
                model,
                "run-id-increment",
                "test"
        );

        assertTrue(record.isSuccess());
        org.mockito.ArgumentCaptor<world.willfrog.agent.graph.SubAgentRunner.SubAgentRequest> reqCaptor =
                org.mockito.ArgumentCaptor.forClass(world.willfrog.agent.graph.SubAgentRunner.SubAgentRequest.class);
        verify(mockSubAgentRunner, times(2)).run(reqCaptor.capture(), eq(model));
        List<world.willfrog.agent.graph.SubAgentRunner.SubAgentRequest> requests = reqCaptor.getAllValues();
        assertEquals("sa_0", requests.get(0).getTaskId());
        assertEquals("sa_1", requests.get(1).getTaskId());
    }

    @Test
    void waitForSubAgent_multipleIdsShouldWaitConcurrently() {
        world.willfrog.agent.graph.SubAgentRunner mockSubAgentRunner =
                org.mockito.Mockito.mock(world.willfrog.agent.graph.SubAgentRunner.class);
        executor.setSubAgentRunner(mockSubAgentRunner);
        ReflectionTestUtils.setField(executor, "subAgentTimeoutSeconds", 5);

        when(mockSubAgentRunner.run(any(), eq(model))).thenAnswer(invocation -> {
            world.willfrog.agent.graph.SubAgentRunner.SubAgentRequest req = invocation.getArgument(0);
            Thread.sleep(300);
            return world.willfrog.agent.graph.SubAgentRunner.SubAgentResult.builder()
                    .success(true)
                    .answer("answer-" + req.getTaskId())
                    .build();
        });

        ReactTodoExecutor.TodoExecutionContext ctx = contextWithSubAgentTools();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_spawn_1", "spawnSubAgent", "{\"goal\":\"子任务1\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_spawn_2", "spawnSubAgent", "{\"goal\":\"子任务2\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_wait", "waitForSubAgent", "{\"sub_agent_ids\":\"sa_0,sa_1\"}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("all done"))
                        .build());

        long startedAt = System.nanoTime();
        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "测试并发等待",
                ctx,
                model,
                "run-concurrent-wait",
                "test"
        );
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertTrue(record.isSuccess());
        assertTrue(elapsed.toMillis() < 550, "waitForSubAgent 应并发等待多个子代理");
    }

    @Test
    void executeWithObservability_shouldFailWhenTruncatedEmptyAfterRetry() {
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage(""))
                        .metadata(dev.langchain4j.model.chat.response.ChatResponseMetadata.builder()
                                .finishReason(dev.langchain4j.model.output.FinishReason.LENGTH)
                                .build())
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage(""))
                        .metadata(dev.langchain4j.model.chat.response.ChatResponseMetadata.builder()
                                .finishReason(dev.langchain4j.model.output.FinishReason.LENGTH)
                                .build())
                        .build());

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "整理结果",
                contextWithMarketSpecs(),
                model,
                "run-truncated",
                "dag_execution"
        );

        assertFalse(record.isSuccess());
        assertEquals("output_truncated:empty_content", record.getSummary());
    }

    @Test
    void executeWithObservability_shouldRegisterPluralDatasetIdsFromToolResult() {
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(toolCallMessage("call_daily", "getExchangeAssetDaily", "{}"))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage("done"))
                        .build());
        when(toolRouter.invokeWithMeta(eq("getExchangeAssetDaily"), anyMap()))
                .thenReturn(invocationResult("""
                        {"ok":true,"data":{"dataset_ids":["aff1111111111111111","aff2222222222222222"]}}
                        """, true));

        ReactTodoExecutor.TodoExecutionContext ctx = contextWithMarketSpecs();
        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "拉取数据",
                ctx,
                model,
                "run-plural-datasets",
                "dag_execution"
        );

        assertTrue(record.isSuccess());
        assertEquals(2, ctx.getDatasetRefs().size());
    }
}

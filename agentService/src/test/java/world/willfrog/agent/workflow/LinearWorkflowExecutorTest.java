package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.service.AgentCitationService;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentRunStateStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinearWorkflowExecutorTest {

    @Mock
    private AgentEventService eventService;
    @Mock
    private AgentPromptService promptService;
    @Mock
    private ReactTodoExecutor reactTodoExecutor;
    @Mock
    private PlanJudge planJudge;
    @Mock
    private PatchPlanner patchPlanner;
    @Mock
    private PlanPatcher planPatcher;
    @Mock
    private AgentRunStateStore stateStore;
    @Mock
    private ChatModel model;

    private LinearWorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new LinearWorkflowExecutor(
                eventService,
                promptService,
                reactTodoExecutor,
                planJudge,
                patchPlanner,
                planPatcher,
                stateStore,
                new AgentCitationService(new ObjectMapper())
        );
        lenient().when(stateStore.loadRunStatus(anyString())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(executor, "defaultMaxToolCalls", 20);
        ReflectionTestUtils.setField(executor, "maxRetriesPerTodoAfterJudge", 2);
        ReflectionTestUtils.setField(executor, "maxPatchesPerRun", 2);

        lenient().when(promptService.dynamicContextPrefix()).thenReturn("今天是2026年03月08日。");
        lenient().when(promptService.dagReactSystemPrompt()).thenReturn("system prompt");
        lenient().when(promptService.finalAnswerStageInstruction()).thenReturn("[Stage: FINAL_ANSWER]\n");

        // 默认 LLM 响应（用于 generateFinalAnswer）
        @SuppressWarnings("unchecked")
        ChatResponse response = mockResponse("最终回答");
        lenient().when(model.chat(any(List.class))).thenReturn(response);
    }

    @Test
    void execute_shouldCompleteWhenTodoSucceeds() {
        when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), anyString(), anyString()
        )).thenReturn(ReactTodoExecutor.TodoExecutionRecord.builder()
                .success(true)
                .output("{\"ok\":true,\"data\":{\"result\":\"success\"}}")
                .summary("Completed in 2 round(s), 1 tool call(s)")
                .toolCallsUsed(1)
                .build());

        WorkflowExecutionResult result = executor.execute(request("run-1", planWithTools(1)));

        assertTrue(result.isSuccess());
        verify(eventService).append(eq("run-1"), eq("u1"), eq("TODO_STARTED"), anyMap());
        verify(eventService).append(eq("run-1"), eq("u1"), eq("TODO_COMPLETED"), anyMap());
    }

    @Test
    void execute_shouldHandleMultipleTodos() {
        when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), anyString(), anyString()
        )).thenReturn(ReactTodoExecutor.TodoExecutionRecord.builder()
                .success(true)
                .output("{\"ok\":true,\"data\":{\"dataset_id\":\"ds_123\"}}")
                .summary("Completed in 1 round(s), 1 tool call(s)")
                .toolCallsUsed(1)
                .build());

        WorkflowExecutionResult result = executor.execute(request("run-multi", planWithTools(2)));

        assertTrue(result.isSuccess());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-multi"), eq("u1"), eq("REACT_LINEAR_EXECUTION_STARTED"), captor.capture());
        assertTrue(captor.getValue().containsKey("items_count"));
        // Verify both todos were started
        verify(eventService, times(2)).append(anyString(), anyString(), eq("TODO_STARTED"), anyMap());
    }

    @Test
    void execute_shouldRespectToolCallLimit() {
        ReflectionTestUtils.setField(executor, "defaultMaxToolCalls", 1);

        when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), anyString(), anyString()
        )).thenReturn(ReactTodoExecutor.TodoExecutionRecord.builder()
                .success(true)
                .output("{\"ok\":true}")
                .summary("ok")
                .toolCallsUsed(1)
                .build());

        // First todo succeeds (uses 1 tool call), second hits limit
        WorkflowExecutionResult result = executor.execute(request("run-limit", planWithTools(2)));

        // Should fail because tool call limit is reached on second todo
        assertFalse(result.isSuccess());
    }

    @Test
    void execute_shouldHandleTodoFailure() {
        when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), anyString(), anyString()
        )).thenReturn(ReactTodoExecutor.TodoExecutionRecord.builder()
                .success(false)
                .output("")
                .summary("Tool call failed after retries")
                .toolCallsUsed(1)
                .build());

        WorkflowExecutionResult result = executor.execute(request("run-fail", planWithTools(1)));

        assertFalse(result.isSuccess());
        verify(eventService).append(eq("run-fail"), eq("u1"), eq("TODO_FAILED"), anyMap());
        verifyNoInteractions(planJudge, patchPlanner, planPatcher);
    }

    @Test
    void execute_planPatchEnabled_shouldApplyPatchAndContinue() {
        when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), anyString(), anyString()
        )).thenReturn(
                ReactTodoExecutor.TodoExecutionRecord.builder()
                        .success(false)
                        .output("")
                        .summary("first failed")
                        .toolCallsUsed(1)
                        .build(),
                ReactTodoExecutor.TodoExecutionRecord.builder()
                        .success(true)
                        .output("{\"ok\":true}")
                        .summary("patched success")
                        .toolCallsUsed(1)
                        .build()
        );
        when(planJudge.judge(any(), any(), anyMap(), anyString(), any()))
                .thenReturn(JudgeDecision.PATCH_PLAN);
        when(patchPlanner.generatePatch(any(), any(), anyMap(), anyString(), any()))
                .thenReturn(PlanPatch.builder()
                        .patchType(PatchType.REPLACE)
                        .targetTodoId("todo_1")
                        .patchData(Map.of("newDescription", "修正后的任务描述"))
                        .reason("fix")
                        .build());
        when(planPatcher.applyPatch(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowRequest request = request("run-patch", planWithTools(1));
        request.setEnablePlanPatch(true);
        WorkflowExecutionResult result = executor.execute(request);

        assertTrue(result.isSuccess());
        verify(planJudge).judge(any(), any(), anyMap(), anyString(), any());
        verify(patchPlanner).generatePatch(any(), any(), anyMap(), anyString(), any());
        verify(planPatcher).applyPatch(any(), any());
    }

    @Test
    void execute_shouldDelegateToReactTodoExecutor() {
        when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), anyString(), anyString()
        )).thenReturn(ReactTodoExecutor.TodoExecutionRecord.builder()
                .success(true)
                .output("{\"ok\":true}")
                .summary("Completed in 3 round(s), 2 tool call(s)")
                .toolCallsUsed(2)
                .build());

        WorkflowExecutionResult result = executor.execute(request("run-delegate", planWithTools(1)));

        assertTrue(result.isSuccess());
        // Verify ReactTodoExecutor was called
        verify(reactTodoExecutor).executeWithObservability(
                eq("查询股票数据 1"),
                any(ReactTodoExecutor.TodoExecutionContext.class),
                eq(model),
                eq("run-delegate"),
                eq("linear_execution")
        );
    }

    @Test
    void execute_shouldTrackTotalToolCalls() {
        when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), anyString(), anyString()
        )).thenReturn(ReactTodoExecutor.TodoExecutionRecord.builder()
                .success(true)
                .output("{\"ok\":true}")
                .summary("ok")
                .toolCallsUsed(3)
                .build());

        WorkflowExecutionResult result = executor.execute(request("run-track", planWithTools(2)));

        assertTrue(result.isSuccess());
        // Each todo uses 3 tool calls, total should be 6
        assertTrue(result.getToolCallsUsed() == 6);
    }

    @Test
    void execute_finalAnswerShouldContainFullOutputWithoutTruncation() {
        String longOutput = "LONG_OUTPUT_" + "x".repeat(1200);
        when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), anyString(), anyString()
        )).thenReturn(ReactTodoExecutor.TodoExecutionRecord.builder()
                .success(true)
                .output(longOutput)
                .summary("ok")
                .toolCallsUsed(1)
                .build());

        WorkflowExecutionResult result = executor.execute(request("run-full-output", planWithTools(1)));

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messageCaptor = ArgumentCaptor.forClass(List.class);
        verify(model).chat(messageCaptor.capture());
        List<ChatMessage> messages = messageCaptor.getValue();
        String finalUserMessage = messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::singleText)
                .reduce((first, second) -> second)
                .orElse("");
        assertTrue(finalUserMessage.contains(longOutput));
        assertEquals(1, result.getToolCallsUsed());
    }

    @Test
    void execute_finalAnswerPromptShouldIncludeRenumberedCitationMap() {
        String output = """
                {"ok":true,"tool":"searchWeb","data":{"citations":[
                  {"index":8,"title":"来源A","url":"https://example.com/a","entityMatch":true,"relevanceJudged":true},
                  {"index":9,"title":"来源A重复","url":"https://example.com/a#section","entityMatch":true,"relevanceJudged":true},
                  {"index":10,"title":"来源B","url":"https://example.com/b","entityMatch":false,"relevanceJudged":false,"relevanceWarning":"低相关"}
                ]}}
                """;
        when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), anyString(), anyString()
        )).thenReturn(ReactTodoExecutor.TodoExecutionRecord.builder()
                .success(true)
                .output(output)
                .summary("ok")
                .toolCallsUsed(1)
                .build());

        WorkflowExecutionResult result = executor.execute(request("run-citations", planWithTools(1)));

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messageCaptor = ArgumentCaptor.forClass(List.class);
        verify(model).chat(messageCaptor.capture());
        String finalUserMessage = messageCaptor.getValue().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::singleText)
                .reduce((first, second) -> second)
                .orElse("");
        assertTrue(finalUserMessage.contains("可引用来源"));
        assertTrue(finalUserMessage.contains("[1] 来源A - https://example.com/a"));
        assertTrue(finalUserMessage.contains("[2] 来源B - https://example.com/b"));
        assertFalse(finalUserMessage.contains("[2] 来源A重复"));
        assertTrue(finalUserMessage.contains("相关性需谨慎"));
    }

    private WorkflowRequest request(String runId, TodoPlan plan) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("u1");
        return WorkflowRequest.builder()
                .run(run)
                .userId("u1")
                .userGoal("查询股票数据并进行分析")
                .plan(plan)
                .model(model)
                .toolSpecifications(List.of(
                        ToolSpecification.builder().name("searchStock").description("搜索股票").build(),
                        ToolSpecification.builder().name("executePython").description("执行Python代码").build()
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
                    .description("查询股票数据 " + i)
                    .dependsOn(List.of())
                    .status(TodoStatus.PENDING)
                    .build());
        }
        return plan;
    }

    private ChatResponse mockResponse(String text) {
        return ChatResponse.builder()
                .aiMessage(new AiMessage(text))
                .metadata(ChatResponseMetadata.builder().build())
                .build();
    }
}

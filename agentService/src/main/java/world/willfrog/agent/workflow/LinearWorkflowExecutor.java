package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.tool.ToolRouter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ReAct 模式的线性工作流执行器。
 *
 * <p>核心特点：
 * <ul>
 *   <li>Todo 只包含简短描述，不包含具体工具参数</li>
 *   <li>执行时委托 {@link ReactTodoExecutor} 执行多轮 ReAct 循环</li>
 *   <li>数据通过 ReAct 消息上下文传递（dataset_refs 映射）</li>
 *   <li>与 DAG 模式共享同一套消息构建逻辑和 System Prompt</li>
 * </ul>
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LinearWorkflowExecutor implements WorkflowExecutor {

    private final AgentEventService eventService;
    private final AgentPromptService promptService;
    private final ToolRouter toolRouter;
    private final AgentObservabilityService observabilityService;
    private final ObjectMapper objectMapper;
    private final ReactTodoExecutor reactTodoExecutor;

    @Value("${agent.flow.workflow.max-tool-calls:20}")
    private int defaultMaxToolCalls;

    private static final String PHASE_LINEAR_EXECUTION = "linear_execution";
    private static final int OUTPUT_PREVIEW_MAX_LENGTH = 500;

    @Override
    public WorkflowExecutionResult execute(WorkflowRequest request) {
        AgentRun run = request.getRun();
        String runId = run.getId();
        String userId = request.getUserId();
        TodoPlan plan = request.getPlan();

        eventService.append(runId, userId, "REACT_LINEAR_EXECUTION_STARTED", Map.of(
                "items_count", plan.getItems().size()
        ));

        // 初始化 ReAct 执行上下文
        Set<String> availableTools = request.getToolSpecifications().stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toSet());
        String userGoal = request.getUserGoal();
        
        List<CompletedTodoInfo> completedTodos = new ArrayList<>();
        Map<String, String> datasetRefs = new HashMap<>();
        int totalToolCalls = 0;

        for (TodoItem item : plan.getItems()) {
            if (totalToolCalls >= defaultMaxToolCalls) {
                eventService.append(runId, userId, "TOOL_CALL_LIMIT_REACHED", Map.of(
                        "limit", defaultMaxToolCalls
                ));
                return buildFailureResult(completedTodos, "Tool call limit reached");
            }

            log.info("Executing Todo {}: {}", item.getId(), item.getDescription());
            
            eventService.append(runId, userId, "TODO_STARTED", Map.of(
                    "todo_id", item.getId(),
                    "description", item.getDescription()
            ));

            // 委托 ReactTodoExecutor 执行多轮 ReAct 循环
            ReactTodoExecutor.TodoExecutionContext todoContext = ReactTodoExecutor.TodoExecutionContext.builder()
                    .userGoal(userGoal)
                    .availableTools(availableTools)
                    .completedTodos(new ArrayList<>(completedTodos))
                    .datasetRefs(new HashMap<>(datasetRefs))
                    .build();
            
            ReactTodoExecutor.TodoExecutionRecord record = reactTodoExecutor.executeWithObservability(
                    item.getDescription(),
                    todoContext,
                    request.getModel(),
                    runId,
                    PHASE_LINEAR_EXECUTION
            );

            totalToolCalls += record.getToolCallsUsed();

            if (!record.isSuccess()) {
                log.warn("Todo {} failed: {}", item.getId(), record.getSummary());
                eventService.append(runId, userId, "TODO_FAILED", Map.of(
                        "todo_id", item.getId(),
                        "summary", record.getSummary()
                ));
                return buildFailureResult(completedTodos, record.getSummary());
            }

            eventService.append(runId, userId, "TODO_COMPLETED", Map.of(
                    "todo_id", item.getId(),
                    "tool_calls_used", record.getToolCallsUsed(),
                    "summary", record.getSummary()
            ));
            
            // 合并新注册的 datasetRefs
            datasetRefs.putAll(todoContext.getDatasetRefs());

            // 将当前结果加入已完成列表
            completedTodos.add(CompletedTodoInfo.builder()
                    .todoId(item.getId())
                    .description(item.getDescription())
                    .output(record.getOutput())
                    .summary(record.getSummary())
                    .build());
        }

        // 生成最终回答
        String finalAnswer = generateFinalAnswer(userGoal, completedTodos, request.getModel());

        return WorkflowExecutionResult.builder()
                .success(true)
                .finalAnswer(finalAnswer)
                .completedItems(new ArrayList<>())
                .toolCallsUsed(totalToolCalls)
                .build();
    }

    private String generateFinalAnswer(String userGoal, 
                                        List<CompletedTodoInfo> completedTodos,
                                        ChatModel model) {
        try {
            List<ChatMessage> messages = new ArrayList<>();

            // 使用统一的 System Prompt
            messages.add(new SystemMessage(promptService.dagReactSystemPrompt()));

            StringBuilder context = new StringBuilder();
            context.append(promptService.dynamicContextPrefix()).append("\n\n");
            context.append("用户问题：").append(userGoal).append("\n\n");
            context.append("已完成的任务：\n");
            for (CompletedTodoInfo todo : completedTodos) {
                context.append(String.format("- %s: %s\n", todo.getDescription(), todo.getSummary()));
                if (todo.getOutput() != null && !todo.getOutput().isEmpty()) {
                    String outputPreview = todo.getOutput().length() > OUTPUT_PREVIEW_MAX_LENGTH 
                            ? todo.getOutput().substring(0, OUTPUT_PREVIEW_MAX_LENGTH) + "..." 
                            : todo.getOutput();
                    context.append("  输出: ").append(outputPreview).append("\n");
                }
            }
            context.append("\n请根据以上所有任务结果，生成对用户问题的最终回答。");
            context.append("\n输出格式: {\"answer\":\"<你的最终回答>\"}");

            messages.add(new UserMessage(context.toString()));

            ChatResponse response = model.chat(messages);
            return response.aiMessage() != null ? response.aiMessage().text() : "";

        } catch (Exception e) {
            log.error("Failed to generate final answer", e);
            return "无法生成回答: " + e.getMessage();
        }
    }

    private WorkflowExecutionResult buildFailureResult(List<CompletedTodoInfo> completedTodos, String errorMessage) {
        StringBuilder combinedOutput = new StringBuilder();
        for (CompletedTodoInfo todo : completedTodos) {
            if (todo.getOutput() != null && !todo.getOutput().isEmpty()) {
                combinedOutput.append(todo.getOutput()).append("\n");
            }
        }

        return WorkflowExecutionResult.builder()
                .success(false)
                .finalAnswer(combinedOutput.toString())
                .failureReason(errorMessage)
                .completedItems(new ArrayList<>())
                .build();
    }
}

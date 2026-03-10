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

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ReAct 模式的线性工作流执行器。
 *
 * <p>核心特点：
 * <ul>
 *   <li>Todo 只包含简短描述，不包含具体工具参数</li>
 *   <li>执行时由 LLM 根据上下文自主决策如何完成每个 Todo</li>
 *   <li>数据通过 ReAct 消息上下文传递（dataset_refs 映射）</li>
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

    @Value("${agent.flow.workflow.max-tool-calls:20}")
    private int defaultMaxToolCalls;

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
        ReactExecutionState state = new ReactExecutionState();
        state.setAvailableTools(request.getToolSpecifications().stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toSet()));
        state.setUserGoal(request.getUserGoal());

        int toolCallCount = 0;
        List<TodoExecutionRecord> records = new ArrayList<>();

        for (TodoItem item : plan.getItems()) {
            if (toolCallCount >= defaultMaxToolCalls) {
                eventService.append(runId, userId, "TOOL_CALL_LIMIT_REACHED", Map.of(
                        "limit", defaultMaxToolCalls
                ));
                return buildResult(records, false, "Tool call limit reached");
            }

            log.info("Executing Todo {}: {}", item.getId(), item.getDescription());

            TodoExecutionRecord record = executeTodoReAct(
                    item, state, request, toolCallCount
            );

            records.add(record);
            toolCallCount += record.getToolCallsUsed();

            if (!record.isSuccess()) {
                log.warn("Todo {} failed: {}", item.getId(), record.getSummary());
                return buildResult(records, false, record.getSummary());
            }

            // 将当前结果加入上下文
            state.addToolCallHistory(ToolCallHistoryItem.builder()
                    .todoId(item.getId())
                    .description(item.getDescription())
                    .output(record.getOutput())
                    .summary(record.getSummary())
                    .build());
        }

        // 生成最终回答
        String finalAnswer = generateFinalAnswer(state, request);

        return WorkflowExecutionResult.builder()
                .success(true)
                .finalAnswer(finalAnswer)
                .completedItems(new ArrayList<>())
                .build();
    }

    private TodoExecutionRecord executeTodoReAct(TodoItem item,
                                                  ReactExecutionState state,
                                                  WorkflowRequest request,
                                                  int currentToolCallCount) {
        String runId = request.getRun().getId();
        String userId = request.getUserId();

        eventService.append(runId, userId, "TODO_STARTED", Map.of(
                "todo_id", item.getId(),
                "description", item.getDescription()
        ));

        try {
            // 构建执行上下文的消息
            List<ChatMessage> messages = buildExecutionMessages(item, state);

            // 调用 LLM 决策
            ChatModel model = request.getModel();
            ChatResponse response = model.chat(messages);
            String llmOutput = response.aiMessage() != null ? response.aiMessage().text() : "";

            // 解析 LLM 输出
            LlmDecision decision = parseLlmDecision(llmOutput);

            if (decision.getToolName() == null) {
                // 直接回答，不需要工具
                return TodoExecutionRecord.builder()
                        .success(true)
                        .output(decision.getAnswer())
                        .summary("Completed without tool call")
                        .toolCallsUsed(0)
                        .build();
            }

            // 执行工具调用
            log.info("LLM decided to call tool: {} for todo {}", decision.getToolName(), item.getId());

            // 添加 dataset_refs 到参数
            Map<String, Object> params = new HashMap<>(decision.getParams());
            if (!state.getDatasetRefs().isEmpty()) {
                params.put("_dataset_refs", state.getDatasetRefs());
            }

            String toolResult = toolRouter.invoke(decision.getToolName(), params);

            // 如果是数据获取工具，提取 dataset_id
            if (isDataTool(decision.getToolName())) {
                extractDatasetId(toolResult, state);
            }

            boolean success = !toolResult.contains("\"ok\":false");

            eventService.append(runId, userId, "TOOL_CALLED", Map.of(
                    "todo_id", item.getId(),
                    "tool_name", decision.getToolName(),
                    "success", success
            ));

            return TodoExecutionRecord.builder()
                    .success(success)
                    .output(toolResult)
                    .summary(success ? "Tool call succeeded" : "Tool call failed")
                    .toolCallsUsed(1)
                    .build();

        } catch (Exception e) {
            log.error("Failed to execute Todo {}", item.getId(), e);
            return TodoExecutionRecord.builder()
                    .success(false)
                    .output("")
                    .summary("Execution failed: " + e.getMessage())
                    .toolCallsUsed(0)
                    .build();
        }
    }

    private List<ChatMessage> buildExecutionMessages(TodoItem item, ReactExecutionState state) {
        List<ChatMessage> messages = new ArrayList<>();

        // System Prompt
        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append("你是金融数据分析助手。你的任务是根据用户的指令，决定如何完成当前任务。\n\n");
        systemPrompt.append("用户原始目标：").append(state.getUserGoal()).append("\n\n");
        systemPrompt.append("可用工具：\n");
        for (String tool : state.getAvailableTools()) {
            systemPrompt.append("  - ").append(tool).append("\n");
        }
        systemPrompt.append("\n数据传递说明：\n");
        systemPrompt.append("当你调用数据获取工具（如 getStockDaily、getIndexDaily 等）时，\n");
        systemPrompt.append("系统会自动将 dataset_id 映射加到上下文中，\n");
        systemPrompt.append("供后续的 executePython 调用使用。\n");

        messages.add(new SystemMessage(systemPrompt.toString()));

        // 历史记录
        for (ToolCallHistoryItem history : state.getToolCallHistory()) {
            messages.add(new UserMessage(String.format(
                    "已完成: %s\n结果: %s",
                    history.getDescription(),
                    history.getSummary()
            )));
        }

        // 当前任务
        StringBuilder userMessage = new StringBuilder();
        userMessage.append(promptService.dynamicContextPrefix()).append("\n\n");
        userMessage.append("当前任务: ").append(item.getDescription()).append("\n\n");

        if (!state.getDatasetRefs().isEmpty()) {
            userMessage.append("已有数据集引用:\n");
            for (Map.Entry<String, String> entry : state.getDatasetRefs().entrySet()) {
                userMessage.append(String.format("  - %s: %s\n", entry.getKey(), entry.getValue()));
            }
            userMessage.append("\n");
        }

        userMessage.append("请决定如何完成这个任务。\n\n");
        userMessage.append("如果需要调用工具，请输出 JSON 格式：\n");
        userMessage.append("{\"tool\":\"toolName\",\"params\":{...},\"reasoning\":\"...\"}\n\n");
        userMessage.append("如果不需要工具，直接回答，请输出：\n");
        userMessage.append("{\"answer\":\"\u4f60的回答\",\"reasoning\":\"...\"}");

        messages.add(new UserMessage(userMessage.toString()));

        return messages;
    }

    private LlmDecision parseLlmDecision(String llmOutput) {
        try {
            String json = extractJson(llmOutput);
            if (json == null) {
                return LlmDecision.builder()
                        .answer(llmOutput)
                        .reasoning("")
                        .build();
            }

            Map<String, Object> map = objectMapper.readValue(json, Map.class);

            if (map.containsKey("answer")) {
                return LlmDecision.builder()
                        .answer((String) map.get("answer"))
                        .reasoning((String) map.get("reasoning"))
                        .build();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) map.get("params");

            return LlmDecision.builder()
                    .toolName((String) map.get("tool"))
                    .params(params != null ? params : new HashMap<>())
                    .reasoning((String) map.get("reasoning"))
                    .build();

        } catch (Exception e) {
            return LlmDecision.builder()
                    .answer(llmOutput)
                    .reasoning("")
                    .build();
        }
    }

    private String extractJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private boolean isDataTool(String toolName) {
        return toolName != null && (
                toolName.contains("Daily") ||
                        toolName.contains("Stock") ||
                        toolName.contains("Index") ||
                        toolName.contains("Fund")
        );
    }

    private void extractDatasetId(String toolResult, ReactExecutionState state) {
        try {
            Map<String, Object> result = objectMapper.readValue(toolResult, Map.class);
            if (result.containsKey("data")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data.containsKey("dataset_id")) {
                    String datasetId = data.get("dataset_id").toString();
                    String path = "/sandbox/input/" + datasetId;
                    state.registerDatasetRef(datasetId, path);
                    log.info("Registered dataset ref: {} -> {}", datasetId, path);
                }
            }
        } catch (Exception e) {
            log.debug("No dataset_id found in tool result");
        }
    }

    private String generateFinalAnswer(ReactExecutionState state, WorkflowRequest request) {
        try {
            List<ChatMessage> messages = new ArrayList<>();

            messages.add(new SystemMessage(
                    "你是金融数据分析助手。请根据已完成的任务，生成对用户问题的最终回答。"
            ));

            StringBuilder context = new StringBuilder();
            context.append("用户问题：").append(request.getUserGoal()).append("\n\n");
            context.append("已完成的任务：\n");
            for (ToolCallHistoryItem history : state.getToolCallHistory()) {
                context.append(String.format("- %s: %s\n", history.getDescription(), history.getSummary()));
            }
            context.append("\n请生成最终回答。");

            messages.add(new UserMessage(context.toString()));

            ChatResponse response = request.getModel().chat(messages);
            return response.aiMessage() != null ? response.aiMessage().text() : "";

        } catch (Exception e) {
            log.error("Failed to generate final answer", e);
            return "无法生成回答: " + e.getMessage();
        }
    }

    private WorkflowExecutionResult buildResult(List<TodoExecutionRecord> records, boolean success, String errorMessage) {
        StringBuilder combinedOutput = new StringBuilder();
        for (TodoExecutionRecord record : records) {
            if (record.getOutput() != null && !record.getOutput().isEmpty()) {
                combinedOutput.append(record.getOutput()).append("\n");
            }
        }

        return WorkflowExecutionResult.builder()
                .success(success)
                .finalAnswer(combinedOutput.toString())
                .failureReason(errorMessage)
                .completedItems(new ArrayList<>())
                .build();
    }

    // 内部状态类

    @Data
    private static class ReactExecutionState {
        private Set<String> availableTools = new HashSet<>();
        private String userGoal = "";
        private List<ToolCallHistoryItem> toolCallHistory = new ArrayList<>();
        private Map<String, String> datasetRefs = new HashMap<>();

        public void addToolCallHistory(ToolCallHistoryItem item) {
            toolCallHistory.add(item);
        }

        public void registerDatasetRef(String datasetId, String path) {
            datasetRefs.put(datasetId, path);
        }
    }

    @Builder
    @Data
    private static class ToolCallHistoryItem {
        private String todoId;
        private String description;
        private String output;
        private String summary;
    }

    @Builder
    @Data
    private static class LlmDecision {
        private String toolName;
        private Map<String, Object> params;
        private String answer;
        private String reasoning;
    }

    @Builder
    @Data
    private static class TodoExecutionRecord {
        private boolean success;
        private String output;
        private String summary;
        private int toolCallsUsed;
    }
}

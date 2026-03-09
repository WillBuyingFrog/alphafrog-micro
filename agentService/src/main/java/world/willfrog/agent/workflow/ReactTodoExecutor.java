package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.tool.ToolRouter;

import java.util.*;

/**
 * ReAct 模式的单个 Todo 执行器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReactTodoExecutor {

    private final AgentPromptService promptService;
    private final ToolRouter toolRouter;
    private final ObjectMapper objectMapper;

    public TodoExecutionRecord execute(String description, TodoExecutionContext context, ChatModel model) {
        try {
            // 构建 ReAct 消息
            List<ChatMessage> messages = buildMessages(description, context);
            
            // 调用 LLM 决策
            ChatResponse response = model.chat(messages);
            String llmOutput = response.aiMessage() != null ? response.aiMessage().text() : "";
            
            // 解析决策
            LlmDecision decision = parseDecision(llmOutput);
            
            if (decision.getToolName() == null) {
                // 直接回答
                return TodoExecutionRecord.builder()
                        .success(true)
                        .output(decision.getAnswer())
                        .summary("Completed without tool")
                        .build();
            }
            
            // 执行工具
            return executeTool(decision, context);
            
        } catch (Exception e) {
            log.error("Failed to execute todo: {}", description, e);
            return TodoExecutionRecord.builder()
                    .success(false)
                    .output("")
                    .summary("Error: " + e.getMessage())
                    .build();
        }
    }

    private List<ChatMessage> buildMessages(String description, TodoExecutionContext context) {
        List<ChatMessage> messages = new ArrayList<>();
        
        // System Prompt
        StringBuilder system = new StringBuilder();
        system.append("你是金融数据分析助手。\n\n");
        system.append("用户目标：").append(context.getUserGoal()).append("\n\n");
        system.append("可用工具：\n");
        for (String tool : context.getAvailableTools()) {
            system.append("  - ").append(tool).append("\n");
        }
        system.append("\n对于数据获取工具，返回结果中会包含 dataset_id，\n");
        system.append("后续工具可以通过 _dataset_refs 参数使用这些数据集。\n");
        
        messages.add(new SystemMessage(system.toString()));
        
        // 历史完成的 Todo
        for (CompletedTodoInfo todo : context.getCompletedTodos()) {
            messages.add(new UserMessage(String.format(
                    "已完成: %s\n结果: %s",
                    todo.getDescription(),
                    todo.getSummary()
            )));
        }
        
        // 当前任务
        StringBuilder userMsg = new StringBuilder();
        userMsg.append(promptService.dynamicContextPrefix()).append("\n\n");
        userMsg.append("当前任务: ").append(description).append("\n\n");
        
        if (!context.getDatasetRefs().isEmpty()) {
            userMsg.append("已有数据集:\n");
            context.getDatasetRefs().forEach((id, path) -> 
                    userMsg.append(String.format("  - %s: %s\n", id, path)));
            userMsg.append("\n");
        }
        
        userMsg.append("请决定如何完成。\n");
        userMsg.append("调用工具输出: {\"tool\":\"...\",\"params\":{...}}\n");
        userMsg.append("直接回答输出: {\"answer\":\"...\"}");
        
        messages.add(new UserMessage(userMsg.toString()));
        
        return messages;
    }

    private LlmDecision parseDecision(String output) {
        try {
            String json = extractJson(output);
            if (json == null) {
                return LlmDecision.builder().answer(output).build();
            }
            
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            
            if (map.containsKey("answer")) {
                return LlmDecision.builder()
                        .answer((String) map.get("answer"))
                        .build();
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) map.getOrDefault("params", Map.of());
            
            return LlmDecision.builder()
                    .toolName((String) map.get("tool"))
                    .params(params)
                    .build();
            
        } catch (Exception e) {
            return LlmDecision.builder().answer(output).build();
        }
    }

    private TodoExecutionRecord executeTool(LlmDecision decision, TodoExecutionContext context) {
        try {
            Map<String, Object> params = new HashMap<>(decision.getParams());
            
            // 添加 dataset_refs
            if (!context.getDatasetRefs().isEmpty()) {
                params.put("_dataset_refs", context.getDatasetRefs());
            }
            
            String result = toolRouter.invoke(decision.getToolName(), params);
            boolean success = !result.contains("\"ok\":false");
            
            return TodoExecutionRecord.builder()
                    .success(success)
                    .output(result)
                    .summary(success ? "Success" : "Failed")
                    .build();
            
        } catch (Exception e) {
            return TodoExecutionRecord.builder()
                    .success(false)
                    .output("")
                    .summary("Tool error: " + e.getMessage())
                    .build();
        }
    }

    private String extractJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    @Builder
    @Data
    public static class TodoExecutionContext {
        private String userGoal;
        private Set<String> availableTools;
        private List<CompletedTodoInfo> completedTodos;
        private Map<String, String> datasetRefs;
    }

    @Builder
    @Data
    private static class LlmDecision {
        private String toolName;
        private Map<String, Object> params;
        private String answer;
    }

    @Builder
    @Data
    public static class TodoExecutionRecord {
        private boolean success;
        private String output;
        private String summary;
    }
}

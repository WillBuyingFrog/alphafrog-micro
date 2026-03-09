package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.tool.ToolRouter;

import java.time.Instant;
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
    private final AgentObservabilityService observabilityService;

    public TodoExecutionRecord execute(String description, TodoExecutionContext context, ChatModel model) {
        return executeWithObservability(description, context, model, null, null);
    }
    
    /**
     * 执行 Todo 并记录完整的可观测性数据。
     * 
     * @param description 任务描述
     * @param context 执行上下文
     * @param model LLM 模型
     * @param runId Run ID（用于观测）
     * @param phase 执行阶段（用于观测）
     * @return 执行记录
     */
    public TodoExecutionRecord executeWithObservability(String description, 
                                                         TodoExecutionContext context, 
                                                         ChatModel model,
                                                         String runId,
                                                         String phase) {
        long llmStartTime = System.currentTimeMillis();
        String llmTraceId = null;
        
        try {
            // 构建 ReAct 消息
            List<ChatMessage> messages = buildMessages(description, context);
            
            // 调用 LLM 决策
            ChatResponse response = model.chat(messages);
            String llmOutput = response.aiMessage() != null ? response.aiMessage().text() : "";
            long llmDurationMs = System.currentTimeMillis() - llmStartTime;
            
            // 记录 LLM 调用
            if (runId != null && !runId.isBlank()) {
                TokenUsage tokenUsage = response.tokenUsage();
                llmTraceId = observabilityService.recordLlmCall(
                        runId,
                        phase != null ? phase : "dag_execution",
                        tokenUsage,
                        llmDurationMs,
                        null, // endpointName - 从上下文中获取
                        null, // modelName
                        null, // errorMessage
                        buildRequestSnapshot(messages, description),
                        llmOutput
                );
            }
            
            // 解析决策
            LlmDecision decision = parseDecision(llmOutput);
            
            if (decision.getToolName() == null) {
                // 直接回答
                return TodoExecutionRecord.builder()
                        .success(true)
                        .output(decision.getAnswer())
                        .summary("Completed without tool")
                        .llmTraceId(llmTraceId)
                        .build();
            }
            
            // 执行工具
            TodoExecutionRecord record = executeTool(decision, context, runId, phase);
            record.setLlmTraceId(llmTraceId);
            return record;
            
        } catch (Exception e) {
            log.error("Failed to execute todo: {}", description, e);
            long llmDurationMs = System.currentTimeMillis() - llmStartTime;
            
            // 记录失败的 LLM 调用
            if (runId != null && !runId.isBlank()) {
                observabilityService.recordLlmCall(
                        runId,
                        phase != null ? phase : "dag_execution",
                        null,
                        llmDurationMs,
                        null,
                        null,
                        e.getMessage(),
                        null,
                        null
                );
            }
            
            return TodoExecutionRecord.builder()
                    .success(false)
                    .output("")
                    .summary("Error: " + e.getMessage())
                    .llmTraceId(llmTraceId)
                    .build();
        }
    }
    
    private Map<String, Object> buildRequestSnapshot(List<ChatMessage> messages, String description) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("stage", "dag_node_decision");
        snapshot.put("description", description);
        snapshot.put("messageCount", messages.size());
        return snapshot;
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

    private TodoExecutionRecord executeTool(LlmDecision decision, 
                                             TodoExecutionContext context,
                                             String runId,
                                             String phase) {
        long toolStartTime = System.currentTimeMillis();
        String toolName = decision.getToolName();
        
        try {
            Map<String, Object> params = new HashMap<>(decision.getParams());
            
            // 添加 dataset_refs
            if (!context.getDatasetRefs().isEmpty()) {
                params.put("_dataset_refs", context.getDatasetRefs());
            }
            
            String result = toolRouter.invoke(toolName, params);
            long toolDurationMs = System.currentTimeMillis() - toolStartTime;
            boolean success = !result.contains("\"ok\":false");
            
            // 记录工具调用观测数据
            if (runId != null && !runId.isBlank()) {
                recordToolCallObservability(runId, phase, toolName, params, result, 
                        toolDurationMs, success, null);
            }
            
            return TodoExecutionRecord.builder()
                    .success(success)
                    .output(result)
                    .summary(success ? "Success" : "Failed")
                    .toolName(toolName)
                    .toolParams(params)
                    .toolDurationMs(toolDurationMs)
                    .build();
            
        } catch (Exception e) {
            long toolDurationMs = System.currentTimeMillis() - toolStartTime;
            
            // 记录失败的工具调用
            if (runId != null && !runId.isBlank()) {
                recordToolCallObservability(runId, phase, toolName, decision.getParams(), "",
                        toolDurationMs, false, e.getMessage());
            }
            
            return TodoExecutionRecord.builder()
                    .success(false)
                    .output("")
                    .summary("Tool error: " + e.getMessage())
                    .toolName(toolName)
                    .toolDurationMs(toolDurationMs)
                    .build();
        }
    }
    
    private void recordToolCallObservability(String runId, String phase, String toolName,
                                              Map<String, Object> params, String output,
                                              long durationMs, boolean success, String errorMessage) {
        try {
            observabilityService.recordToolCall(
                    runId,
                    phase != null ? phase : "dag_execution",
                    toolName,
                    params,
                    output,
                    durationMs,
                    success,
                    false, // cacheEligible
                    false, // cacheHit
                    null,  // cacheKey
                    null,  // cacheSource
                    0L,    // cacheTtlRemainingMs
                    0L,    // estimatedSavedDurationMs
                    errorMessage
            );
        } catch (Exception e) {
            log.warn("Failed to record tool call observability: {}", e.getMessage());
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
        
        // 观测数据字段
        private String llmTraceId;
        private String toolName;
        private Map<String, Object> toolParams;
        private Long toolDurationMs;
        private Instant startedAt;
        private Instant completedAt;
    }
}

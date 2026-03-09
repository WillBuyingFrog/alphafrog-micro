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
        return executeWithRetry(description, context, model, runId, phase, 0);
    }
    
    /**
     * 执行 Todo 带重试机制。
     * 
     * @param retryCount 当前重试次数
     */
    private TodoExecutionRecord executeWithRetry(String description, 
                                                  TodoExecutionContext context, 
                                                  ChatModel model,
                                                  String runId,
                                                  String phase,
                                                  int retryCount) {
        final int MAX_RETRIES = 2;
        long llmStartTime = System.currentTimeMillis();
        String llmTraceId = null;
        
        try {
            // 构建 ReAct 消息（重试时添加错误上下文）
            List<ChatMessage> messages = buildMessagesWithRetryContext(
                    description, context, retryCount
            );
            
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
                        null,
                        null,
                        null,
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
                        .retryCount(retryCount)
                        .build();
            }
            
            // 执行工具
            TodoExecutionRecord record = executeTool(decision, context, runId, phase);
            record.setLlmTraceId(llmTraceId);
            record.setRetryCount(retryCount);
            
            // 如果失败且未达到最大重试次数，进行重试
            if (!record.isSuccess() && retryCount < MAX_RETRIES) {
                String errorHint = extractErrorHint(record.getOutput());
                log.warn("Todo execution failed, will retry {}/{}: {}, error: {}", 
                        retryCount + 1, MAX_RETRIES, description, errorHint);
                
                // 构建带错误提示的新上下文
                TodoExecutionContext retryContext = buildRetryContext(context, errorHint);
                
                return executeWithRetry(description, retryContext, model, runId, phase, retryCount + 1);
            }
            
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
            
            // 异常时也尝试重试
            if (retryCount < MAX_RETRIES) {
                log.warn("Todo execution exception, will retry {}/{}: {}", 
                        retryCount + 1, MAX_RETRIES, e.getMessage());
                return executeWithRetry(description, context, model, runId, phase, retryCount + 1);
            }
            
            return TodoExecutionRecord.builder()
                    .success(false)
                    .output("")
                    .summary("Error after " + (MAX_RETRIES + 1) + " attempts: " + e.getMessage())
                    .llmTraceId(llmTraceId)
                    .retryCount(retryCount)
                    .build();
        }
    }
    
    /**
     * 构建带重试上下文的 ReAct 消息。
     */
    private List<ChatMessage> buildMessagesWithRetryContext(String description, 
                                                             TodoExecutionContext context,
                                                             int retryCount) {
        List<ChatMessage> messages = buildMessages(description, context);
        
        // 如果是重试，在最后添加重试提示
        if (retryCount > 0) {
            String retryHint = String.format(
                    "\n\n⚠️ 这是第 %d 次重试。之前的尝试失败了，请仔细检查工具参数名是否与规范完全一致！",
                    retryCount
            );
            
            // 修改最后一条 UserMessage，添加重试提示
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage msg = messages.get(i);
                if (msg instanceof UserMessage) {
                    String text = ((UserMessage) msg).singleText();
                    messages.set(i, new UserMessage(text + retryHint));
                    break;
                }
            }
        }
        
        return messages;
    }
    
    /**
     * 构建带重试提示的新上下文。
     */
    private TodoExecutionContext buildRetryContext(TodoExecutionContext original, String errorHint) {
        // 复制原上下文，添加错误提示到 completedTodos
        List<CompletedTodoInfo> updatedTodos = new ArrayList<>(original.getCompletedTodos());
        updatedTodos.add(CompletedTodoInfo.builder()
                .todoId("_retry_hint_")
                .description("Previous attempt failed with error")
                .output(errorHint)
                .summary("Please fix the parameter names and try again. " +
                        "Ensure you use the exact parameter names specified in the tool documentation.")
                .build());
        
        return TodoExecutionContext.builder()
                .userGoal(original.getUserGoal())
                .availableTools(original.getAvailableTools())
                .completedTodos(updatedTodos)
                .datasetRefs(original.getDatasetRefs())
                .build();
    }
    
    /**
     * 从工具输出中提取错误提示。
     */
    private String extractErrorHint(String output) {
        try {
            Map<String, Object> result = objectMapper.readValue(output, Map.class);
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            if (error != null) {
                String message = (String) error.get("message");
                String code = (String) error.get("code");
                if (code != null && code.equals("NO_DATA") && message != null 
                        && message.contains("keyword")) {
                    return "Invalid keyword parameter. Use 'keyword' not 'keywords' or 'query'.";
                }
                return message != null ? message : code;
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return output;
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
        
        // System Prompt - 包含详细的工具参数说明
        StringBuilder system = new StringBuilder();
        system.append("你是金融数据分析助手。\n\n");
        system.append("用户目标：").append(context.getUserGoal()).append("\n\n");
        
        // 添加工具参数规范说明
        system.append("可用工具及参数规范（必须严格使用指定的参数名）：\n\n");
        
        for (String tool : context.getAvailableTools()) {
            String paramSpec = getToolParamSpec(tool);
            system.append(String.format("  %s: %s\n", tool, paramSpec));
        }
        
        system.append("\n重要提示：\n");
        system.append("1. 必须严格使用上述指定的参数名，否则工具调用会失败\n");
        system.append("2. 对于数据获取工具，返回结果中会包含 dataset_id\n");
        system.append("3. 后续工具可以通过 _dataset_refs 参数使用这些数据集\n");
        
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
        userMsg.append("调用工具输出格式: {\"tool\":\"<工具名>\",\"params\":{<参数名>:<参数值>}}\n");
        userMsg.append("直接回答输出格式: {\"answer\":\"<你的回答>\"}\n");
        userMsg.append("\n警告：params 中的参数名必须与工具规范完全一致！");
        
        messages.add(new UserMessage(userMsg.toString()));
        
        return messages;
    }
    
    /**
     * 获取工具的参数规范说明。
     */
    private String getToolParamSpec(String toolName) {
        return switch (toolName) {
            case "searchIndex" -> "{\"keyword\": \"<搜索关键词>\"}";
            case "searchStock" -> "{\"keyword\": \"<搜索关键词>\"}";
            case "searchFund" -> "{\"keyword\": \"<搜索关键词>\"}";
            case "getIndexDaily" -> "{\"ts_code\": \"<指数代码>\", \"start_date\": \"YYYYMMDD\", \"end_date\": \"YYYYMMDD\"}";
            case "getStockDaily" -> "{\"ts_code\": \"<股票代码>\", \"start_date\": \"YYYYMMDD\", \"end_date\": \"YYYYMMDD\"}";
            case "getFundDaily" -> "{\"ts_code\": \"<基金代码>\", \"start_date\": \"YYYYMMDD\", \"end_date\": \"YYYYMMDD\"}";
            case "getIndexInfo" -> "{\"ts_code\": \"<指数代码>\"}";
            case "getStockInfo" -> "{\"ts_code\": \"<股票代码>\"}";
            case "executePython" -> "{\"code\": \"<Python代码>\", \"libraries\": [\"<可选的库>\"]}";
            default -> "{...}";
        };
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
        
        // 重试相关
        @Builder.Default
        private int retryCount = 0;
    }
}

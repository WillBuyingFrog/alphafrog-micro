package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.tool.ToolRouter;

import java.time.Instant;
import java.util.*;

/**
 * ReAct 模式的单个 Todo 执行器。
 *
 * <p>每个 Todo 内部运行一个多轮 ReAct 循环：
 * LLM 决策 → 工具调用 → 结果注入上下文 → 再次 LLM 决策 → ...
 * 直到 LLM 输出 {"answer":"..."} 表示本 Todo 完成，或达到最大调用次数。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReactTodoExecutor {

    private final AgentPromptService promptService;
    private final ToolRouter toolRouter;
    private final ObjectMapper objectMapper;
    private final AgentObservabilityService observabilityService;

    @Value("${agent.flow.react.max-calls-per-todo:10}")
    private int maxCallsPerTodo;

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
     * 重试的粒度是"整个 Todo 的 ReAct 循环"。
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
        
        try {
            TodoExecutionRecord record = executeReActLoop(description, context, model, runId, phase, retryCount);
            
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
                    .retryCount(retryCount)
                    .build();
        }
    }

    /**
     * 多轮 ReAct 循环：在单个 Todo 内持续调用 LLM 和工具，直到 LLM 输出 answer 或达到上限。
     *
     * <p>上下文链条示例：
     * <pre>
     * [System: dagReactSystemPrompt + 上下文]
     * [User: 当前任务描述]
     * ^[CoT: LLM 决定调用工具 A]         ← Round 1
     * [User: 工具 A 的结果]
     * ^[CoT: LLM 分析结果，决定调用工具 B] ← Round 2
     * [User: 工具 B 的结果]
     * ^[CoT: 任务完成，输出 answer]        ← Round 3
     * </pre>
     */
    private TodoExecutionRecord executeReActLoop(String description,
                                                  TodoExecutionContext context,
                                                  ChatModel model,
                                                  String runId,
                                                  String phase,
                                                  int retryCount) {
        // 构建初始 ReAct 消息（包含重试上下文）
        List<ChatMessage> messages = buildMessagesWithRetryContext(description, context, retryCount);
        
        int callCount = 0;
        int toolCallsUsed = 0;
        String lastLlmTraceId = null;
        String lastOutput = "";

        while (callCount < maxCallsPerTodo) {
            long llmStartTime = System.currentTimeMillis();
            String llmTraceId = null;
            
            // 调用 LLM 决策
            ChatResponse response = model.chat(messages);
            String llmOutput = response.aiMessage() != null ? response.aiMessage().text() : "";
            long llmDurationMs = System.currentTimeMillis() - llmStartTime;
            
            // 将 CoT（Thought）加入上下文
            messages.add(AiMessage.from(llmOutput));
            
            // 记录 LLM 调用（每次单独记录）
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
            lastLlmTraceId = llmTraceId;
            callCount++;
            
            // 解析决策
            LlmDecision decision = parseDecision(llmOutput);
            
            if (decision.getToolName() == null) {
                // LLM 输出 {"answer":"..."} 表示本 Todo 完成
                return TodoExecutionRecord.builder()
                        .success(true)
                        .output(decision.getAnswer())
                        .summary("Completed in " + callCount + " round(s), " + toolCallsUsed + " tool call(s)")
                        .llmTraceId(lastLlmTraceId)
                        .retryCount(retryCount)
                        .toolCallsUsed(toolCallsUsed)
                        .build();
            }
            
            // 设置 DecisionContext，让 ToolTrace 能关联到此 LLM 决策
            if (llmTraceId != null) {
                String excerpt = llmOutput.length() > 200 
                        ? llmOutput.substring(0, 200) : llmOutput;
                AgentContext.setDecisionContext(
                        llmTraceId,
                        phase != null ? phase : "dag_execution",
                        excerpt
                );
            }
            
            // 执行工具
            String toolResult;
            boolean toolSuccess;
            try {
                TodoExecutionRecord toolRecord = executeTool(decision, context, runId, phase);
                toolResult = toolRecord.getOutput();
                toolSuccess = toolRecord.isSuccess();
                toolCallsUsed++;
            } finally {
                AgentContext.clearDecisionContext();
            }
            
            lastOutput = toolResult;
            
            // 从工具结果中提取 dataset_id，更新 context 的 datasetRefs
            extractAndRegisterDatasetRef(toolResult, context);
            
            // 将工具结果（Observation）注入上下文，供下一轮 LLM 使用
            String observation = toolSuccess 
                    ? "[工具结果]\n" + toolResult
                    : "[工具调用失败]\n" + toolResult;
            messages.add(new UserMessage(observation));
            
            // 如果工具失败，继续循环让 LLM 决定下一步（重试或换方案）
            if (!toolSuccess) {
                log.warn("Tool {} failed in ReAct round {}, LLM will decide next step", 
                        decision.getToolName(), callCount);
            }
        }
        
        // 达到最大调用次数限制
        log.warn("ReAct loop reached max calls ({}) for todo: {}", maxCallsPerTodo, description);
        return TodoExecutionRecord.builder()
                .success(false)
                .output(lastOutput)
                .summary("Reached max call limit (" + maxCallsPerTodo + "), " + toolCallsUsed + " tool call(s)")
                .llmTraceId(lastLlmTraceId)
                .retryCount(retryCount)
                .toolCallsUsed(toolCallsUsed)
                .build();
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
            StringBuilder retryHint = new StringBuilder();
            retryHint.append("\n\n");
            retryHint.append("╔══════════════════════════════════════════════════════════════╗\n");
            retryHint.append(String.format("║ ⚠️  这是第 %d 次重试                                          ║\n", retryCount));
            retryHint.append("╚══════════════════════════════════════════════════════════════╝\n\n");
            retryHint.append("之前的尝试失败了。请仔细检查：\n");
            retryHint.append("1. 工具参数名是否与 System Prompt 中的规范完全一致\n");
            retryHint.append("2. 是否遗漏了必需参数（如 executePython 的 dataset_ids）\n");
            retryHint.append("3. 数据集ID是否来自'已有数据集'列表\n\n");
            retryHint.append("如果再次失败，请参考 '_retry_hint_' 中的详细修正建议。");
            
            // 修改最后一条 UserMessage，添加重试提示
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage msg = messages.get(i);
                if (msg instanceof UserMessage) {
                    String text = ((UserMessage) msg).singleText();
                    messages.set(i, new UserMessage(text + retryHint.toString()));
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
        
        // 构建详细的错误提示，包含正确的参数规范
        StringBuilder detailedHint = new StringBuilder();
        detailedHint.append("错误信息：").append(errorHint).append("\n\n");
        
        // 针对特定错误提供具体的修正建议
        if (errorHint.contains("dataset_ids") || errorHint.contains("MISSING_DATASET_IDS")) {
            detailedHint.append("修正建议：\n");
            detailedHint.append("1. executePython 工具需要 dataset_ids 参数，该参数是必需的\n");
            detailedHint.append("2. dataset_ids 必须使用上述'已有数据集'中的ID\n");
            detailedHint.append("3. 单数据集：dataset_ids: \"dataset_xxx\"\n");
            detailedHint.append("4. 多数据集：dataset_ids: \"dataset_xxx,dataset_yyy\"（逗号分隔）\n");
            detailedHint.append("5. 代码中需要遍历 /sandbox/input/*/ 读取数据\n\n");
            detailedHint.append("正确示例：\n");
            detailedHint.append("{\"tool\":\"executePython\",\"params\":{\"dataset_ids\":\"xxx\",\"code\":\"import pandas as pd; ...\"}}");
        } else if (errorHint.contains("keyword")) {
            detailedHint.append("修正建议：\n");
            detailedHint.append("搜索类工具必须使用 'keyword' 参数（不是 'keywords' 或 'query'）\n");
            detailedHint.append("正确示例：{\"tool\":\"searchIndex\",\"params\":{\"keyword\":\"沪深300\"}}");
        } else {
            detailedHint.append("修正建议：\n");
            detailedHint.append("请确保使用正确的参数名，参考 System Prompt 中的工具规范。\n");
            detailedHint.append("特别注意 executePython 的 dataset_ids 参数是必需的！");
        }
        
        updatedTodos.add(CompletedTodoInfo.builder()
                .todoId("_retry_hint_")
                .description("⚠️ 前一次尝试失败，需要修正")
                .output(detailedHint.toString())
                .summary("请根据错误信息修正参数后重试。特别注意参数名必须完全匹配规范。")
                .build());
        
        return TodoExecutionContext.builder()
                .userGoal(original.getUserGoal())
                .availableTools(original.getAvailableTools())
                .completedTodos(updatedTodos)
                .datasetRefs(original.getDatasetRefs())
                .build();
    }
    
    /**
     * 从工具结果中提取 dataset_id 并注册到上下文。
     */
    private void extractAndRegisterDatasetRef(String toolResult, TodoExecutionContext context) {
        try {
            Map<String, Object> result = objectMapper.readValue(toolResult, Map.class);
            if (result.containsKey("data")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data != null && data.containsKey("dataset_id")) {
                    String datasetId = data.get("dataset_id").toString();
                    String path = "/sandbox/input/" + datasetId;
                    context.getDatasetRefs().put(datasetId, path);
                    log.info("Registered dataset ref: {} -> {}", datasetId, path);
                }
            }
        } catch (Exception e) {
            log.debug("No dataset_id found in tool result");
        }
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
        
        // 序列化完整的 messages 数组（包含 role 和 content）
        List<Map<String, String>> messageList = new ArrayList<>();
        for (ChatMessage msg : messages) {
            Map<String, String> msgMap = new HashMap<>();
            if (msg instanceof SystemMessage) {
                msgMap.put("role", "system");
                msgMap.put("content", ((SystemMessage) msg).text());
            } else if (msg instanceof UserMessage) {
                msgMap.put("role", "user");
                msgMap.put("content", ((UserMessage) msg).singleText());
            } else if (msg instanceof AiMessage) {
                msgMap.put("role", "assistant");
                msgMap.put("content", ((AiMessage) msg).text());
            } else {
                msgMap.put("role", "unknown");
                msgMap.put("content", msg.toString());
            }
            messageList.add(msgMap);
        }
        snapshot.put("messages", messageList);
        
        return snapshot;
    }

    private List<ChatMessage> buildMessages(String description, TodoExecutionContext context) {
        List<ChatMessage> messages = new ArrayList<>();
        
        // System Prompt - 从配置文件加载，包含完整的工具参数规范
        String baseSystemPrompt = promptService.dagReactSystemPrompt();
        
        // 动态添加用户目标和可用工具列表
        StringBuilder system = new StringBuilder(baseSystemPrompt);
        system.append("\n\n## 当前上下文\n\n");
        system.append("用户目标：").append(context.getUserGoal()).append("\n\n");
        
        // 添加可用的具体工具列表
        if (!context.getAvailableTools().isEmpty()) {
            system.append("当前可用工具：").append(String.join(", ", context.getAvailableTools())).append("\n");
        }
        
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
            userMsg.append("已有数据集 (可用于 dataset_ids 参数):\n");
            context.getDatasetRefs().forEach((id, path) -> 
                    userMsg.append(String.format("  - %s\n", id)));
            userMsg.append("\n");
            userMsg.append("⚠️ 注意：如果调用 executePython，必须将上述 dataset ID 通过 dataset_ids 参数传入！\n\n");
        }
        
        userMsg.append("请决定如何完成。\n");
        userMsg.append("调用工具输出格式: {\"tool\":\"<工具名>\",\"params\":{<参数名>:<参数值>}}\n");
        userMsg.append("直接回答输出格式: {\"answer\":\"<你的回答>\"}\n");
        userMsg.append("\n⚠️ 警告：params 中的参数名必须与工具规范完全一致！");
        
        messages.add(new UserMessage(userMsg.toString()));
        
        return messages;
    }
    
    /**
     * 获取工具的参数规范说明（用于错误提示和日志）。
     * 注意：System Prompt 中的规范来自 promptService.dagReactSystemPrompt() 配置文件。
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
            case "executePython" -> "{\"code\": \"<Python代码>\", \"dataset_ids\": \"<必需：数据集ID，逗号分隔>\", \"libraries\": \"<可选：库名逗号分隔>\"}";
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
        
        // ReAct 循环统计
        @Builder.Default
        private int toolCallsUsed = 0;
        
        // 重试相关
        @Builder.Default
        private int retryCount = 0;
    }
}

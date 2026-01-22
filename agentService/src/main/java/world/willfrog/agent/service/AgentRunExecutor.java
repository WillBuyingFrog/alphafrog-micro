package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.agent.tool.MarketDataTools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunExecutor {

    private final AgentRunMapper runMapper;
    private final AgentEventService eventService;
    private final AgentAiServiceFactory aiServiceFactory;
    private final MarketDataTools marketDataTools;
    private final ObjectMapper objectMapper;

    /**
     * 异步执行入口，避免阻塞 RPC 请求线程。
     *
     * @param runId 任务 ID
     */
    @Async
    public void executeAsync(String runId) {
        try {
            execute(runId);
        } catch (Exception e) {
            log.error("Agent run execute failed: runId={}", runId, e);
        }
    }

    /**
     * 执行单个 Agent run，使用 OpenRouter Native Tool Calling 循环。
     *
     * @param runId 任务 ID
     */
    public void execute(String runId) {
        AgentRun run = runMapper.findById(runId);
        if (run == null) {
            log.warn("Agent run not found, ignore execute: {}", runId);
            return;
        }
        if (run.getStatus() == AgentRunStatus.CANCELED || run.getStatus() == AgentRunStatus.COMPLETED) {
            log.info("Agent run already terminal, skip execute: {} status={}", runId, run.getStatus());
            return;
        }

        String userId = run.getUserId();
        try {
            if (!eventService.isRunnable(runId, userId)) {
                return;
            }

            runMapper.updateStatus(runId, userId, AgentRunStatus.EXECUTING);
            eventService.append(runId, userId, "EXECUTION_STARTED", mapOf("run_id", runId));

            String endpointName = eventService.extractEndpointName(run.getExt());
            String modelName = eventService.extractModelName(run.getExt());
            ChatLanguageModel chatModel = aiServiceFactory.buildChatModel(endpointName, modelName);

            String userGoal = eventService.extractUserGoal(run.getExt());
            
            // 1. Prepare Tools
            List<ToolSpecification> toolSpecifications = ToolSpecifications.toolSpecificationsFrom(marketDataTools);

            // 2. Prepare Conversation
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage("You are an expert financial agent. Use the provided tools to retrieve market data and answer the user's question accurately. If you cannot find the exact data, try searching with relevant keywords."));
            messages.add(new UserMessage(userGoal));

            // 3. Execution Loop
            int maxSteps = 15;
            String finalAnswer = "";
            String executionLog = "";

            for (int i = 0; i < maxSteps; i++) {
                if (!eventService.isRunnable(runId, userId)) {
                    return;
                }

                // Call LLM
                Response<AiMessage> response = chatModel.generate(messages, toolSpecifications);
                AiMessage aiMessage = response.content();
                messages.add(aiMessage);

                if (aiMessage.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                         String toolName = toolRequest.name();
                         String argsJson = toolRequest.arguments();
                         
                         Map<String, Object> startPayload = new HashMap<>();
                         startPayload.put("tool_name", toolName);
                         startPayload.put("parameters", safeJson(argsJson));
                         eventService.append(runId, userId, "TOOL_CALL_STARTED", startPayload);
                         
                         Map<String, Object> params = jsonToMap(argsJson);
                         String result = invokeTool(toolName, params);
                         
                         Map<String, Object> finishPayload = new HashMap<>();
                         finishPayload.put("tool_name", toolName);
                         finishPayload.put("success", !result.startsWith("Tool invocation error"));
                         finishPayload.put("result_preview", result);
                         eventService.append(runId, userId, "TOOL_CALL_FINISHED", finishPayload);

                         messages.add(ToolExecutionResultMessage.from(toolRequest, result));
                         executionLog += "Tool: " + toolName + "\nResult: " + result + "\n\n";
                    }
                } else {
                    finalAnswer = aiMessage.text();
                    break;
                }
            }
            
            if (finalAnswer == null || finalAnswer.isBlank()) {
                finalAnswer = "Agent stopped after reaching max steps without final answer.";
            }

            // 4. Complete
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("user_goal", userGoal);
            snapshot.put("execution_log", executionLog);
            snapshot.put("answer", finalAnswer);
            String snapshotJson = objectMapper.writeValueAsString(snapshot);

            runMapper.updateSnapshot(runId, userId, AgentRunStatus.COMPLETED, snapshotJson, true, null);
            eventService.append(runId, userId, "COMPLETED", mapOf("answer", finalAnswer));

        } catch (Exception e) {
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("Execution error", e);
            runMapper.updateSnapshot(runId, userId, AgentRunStatus.FAILED, run.getSnapshotJson(), true, err);
            eventService.append(runId, userId, "FAILED", mapOf("error", err));
        }
    }

    /**
     * 工具调用分发（按 tool_name 路由到具体实现）。
     */
    private String invokeTool(String toolName, Map<String, Object> params) {
        try {
            return switch (toolName) {
                case "getStockInfo" -> marketDataTools.getStockInfo(str(params.get("tsCode"), params.get("ts_code"))); 
                case "getStockDaily" -> marketDataTools.getStockDaily(
                        str(params.get("tsCode"), params.get("ts_code")),
                        str(params.get("startDateStr"), params.get("start_date")),
                        str(params.get("endDateStr"), params.get("end_date"))
                );
                case "searchFund" -> marketDataTools.searchFund(str(params.get("keyword"), params.get("query")));
                case "getIndexInfo" -> marketDataTools.getIndexInfo(str(params.get("tsCode"), params.get("ts_code")));
                case "getIndexDaily" -> marketDataTools.getIndexDaily(
                        str(params.get("tsCode"), params.get("ts_code")),
                        str(params.get("startDateStr"), params.get("start_date")),
                        str(params.get("endDateStr"), params.get("end_date"))
                );
                case "searchIndex" -> marketDataTools.searchIndex(str(params.get("keyword"), params.get("query")));
                default -> "Unsupported tool: " + toolName;
            };
        } catch (Exception e) {
            return "Tool invocation error: " + e.getMessage();
        }
    }

    private String str(Object... candidates) {
        for (Object c : candidates) {
            if (c == null) {
                continue;
            }
            String s = String.valueOf(c).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "";
    }

    private Map<String, Object> jsonToMap(String json) {
        try {
             JsonNode node = objectMapper.readTree(json);
             return jsonNodeToMap(node);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> jsonNodeToMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Map.of();
        }
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, Object> map = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode v = entry.getValue();
            if (v == null || v.isNull()) {
                map.put(entry.getKey(), null);
            } else if (v.isTextual()) {
                map.put(entry.getKey(), v.asText());
            } else if (v.isNumber()) {
                map.put(entry.getKey(), v.numberValue());
            } else if (v.isBoolean()) {
                map.put(entry.getKey(), v.asBoolean());
            } else {
                map.put(entry.getKey(), v.toString());
            }
        }
        return map;
    }

    private Object safeJson(String jsonText) {
        if (jsonText == null) {
            return null;
        }
        try {
            return objectMapper.readTree(jsonText);
        } catch (Exception e) {
            return jsonText;
        }
    }

    private Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> map = new HashMap<>();
        map.put(k, v);
        return map;
    }
}
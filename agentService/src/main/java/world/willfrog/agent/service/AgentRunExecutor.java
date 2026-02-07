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
import world.willfrog.agent.tool.PythonSandboxTools;
import world.willfrog.agent.context.AgentContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
/**
 * Agent run 执行器。
 * <p>
 * 执行流程：
 * 1. 进入 EXECUTING 并写入事件；
 * 2. 优先尝试并行图执行；
 * 3. 并行未接管时回退到串行 Tool Calling 循环；
 * 4. 落库最终结果并更新状态。
 */
public class AgentRunExecutor {

    /** run 主表读写。 */
    private final AgentRunMapper runMapper;
    /** 事件服务。 */
    private final AgentEventService eventService;
    /** 模型构建工厂（根据 endpoint/model 动态选型）。 */
    private final AgentAiServiceFactory aiServiceFactory;
    /** 行情工具集合。 */
    private final MarketDataTools marketDataTools;
    /** Python 沙箱工具集合。 */
    private final PythonSandboxTools pythonSandboxTools;
    /** 工具统一路由。 */
    private final world.willfrog.agent.tool.ToolRouter toolRouter;
    /** 并行图执行器。 */
    private final world.willfrog.agent.graph.ParallelGraphExecutor parallelGraphExecutor;
    /** 运行态缓存。 */
    private final AgentRunStateStore stateStore;
    /** JSON 工具。 */
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
            AgentContext.setRunId(runId); // Set Context

            if (!eventService.isRunnable(runId, userId)) {
                return;
            }

            runMapper.updateStatus(runId, userId, AgentRunStatus.EXECUTING);
            eventService.append(runId, userId, "EXECUTION_STARTED", mapOf("run_id", runId));
            stateStore.markRunStatus(runId, AgentRunStatus.EXECUTING.name());

            // endpoint/model 允许请求级覆盖，未指定时由 resolver 走默认配置。
            String endpointName = eventService.extractEndpointName(run.getExt());
            String modelName = eventService.extractModelName(run.getExt());
            ChatLanguageModel chatModel = aiServiceFactory.buildChatModel(endpointName, modelName);

            String userGoal = eventService.extractUserGoal(run.getExt());
            
            // 1. Prepare Tools
            List<ToolSpecification> toolSpecifications = new ArrayList<>();
            toolSpecifications.addAll(ToolSpecifications.toolSpecificationsFrom(marketDataTools));
            toolSpecifications.addAll(ToolSpecifications.toolSpecificationsFrom(pythonSandboxTools));

            // 1.5 Try parallel graph execution (LangGraph4j)
            if (parallelGraphExecutor.isEnabled()) {
                boolean handled = parallelGraphExecutor.execute(run, userId, userGoal, chatModel, toolSpecifications);
                if (handled) {
                    return;
                }
                // 并行未接管时显式记录回退事件，便于排查为什么进入串行流程。
                eventService.append(runId, userId, "PARALLEL_FALLBACK_TO_SERIAL", Map.of(
                        "reason", "parallel_executor_returned_false",
                        "plan_valid_hint", stateStore.loadPlanValid(runId).map(String::valueOf).orElse("unknown"),
                        "state_status_hint", stateStore.loadRunStatus(runId).orElse("unknown")
                ));
            }

            // 2. Prepare Conversation
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage("""
                You are an expert financial agent. Use the provided tools to retrieve market data and answer the user's question accurately.
                
                IMPORTANT: You typically do NOT know the exact TS codes (e.g., 000300.SH) in the database. 
                You MUST always use 'searchStock', 'searchFund', or 'searchIndex' to find the correct ts_code BEFORE calling 'getStockDaily', 'getIndexDaily', or other code-based tools. 
                Never guess the code.
                
                When you get a 'dataset_id' from a market data tool, you MUST use the 'executePython' tool to analyze it.
                The python environment has 'numpy', 'pandas', 'matplotlib', and 'scipy' pre-installed. Please prioritize using these libraries for data processing and calculation.
                """));
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
                    // 模型要求调用工具：逐个执行并把结果回灌给模型。
                    for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                         String toolName = toolRequest.name();
                         String argsJson = toolRequest.arguments();
                         
                         Map<String, Object> startPayload = new HashMap<>();
                         startPayload.put("tool_name", toolName);
                         startPayload.put("parameters", safeJson(argsJson));
                         eventService.append(runId, userId, "TOOL_CALL_STARTED", startPayload);
                         
                         Map<String, Object> params = jsonToMap(argsJson);
                         String result = toolRouter.invoke(toolName, params);
                         
                         Map<String, Object> finishPayload = new HashMap<>();
                         finishPayload.put("tool_name", toolName);
                         finishPayload.put("success", !result.startsWith("Tool invocation error"));
                         finishPayload.put("result_preview", result);
                         eventService.append(runId, userId, "TOOL_CALL_FINISHED", finishPayload);

                         messages.add(ToolExecutionResultMessage.from(toolRequest, result));
                         executionLog += "Tool: " + toolName + "\nResult: " + result + "\n\n";
                    }
                } else {
                    // 无工具请求时，视为模型已产出最终答案。
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
            stateStore.markRunStatus(runId, AgentRunStatus.COMPLETED.name());

        } catch (Exception e) {
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("Execution error", e);
            runMapper.updateSnapshot(runId, userId, AgentRunStatus.FAILED, run.getSnapshotJson(), true, err);
            eventService.append(runId, userId, "FAILED", mapOf("error", err));
            stateStore.markRunStatus(runId, AgentRunStatus.FAILED.name());
        } finally {
            AgentContext.clear(); // Clear Context
        }
    }

    /**
     * 将 JSON 字符串解析为 Map，解析失败返回空 Map。
     *
     * @param json JSON 文本
     * @return 参数 Map
     */
    private Map<String, Object> jsonToMap(String json) {
        try {
             JsonNode node = objectMapper.readTree(json);
             return jsonNodeToMap(node);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * JsonNode 对象节点转 Map，支持常用基础类型。
     *
     * @param node JsonNode
     * @return 参数 Map
     */
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

    /**
     * 尝试把 JSON 文本解析为 JsonNode，失败时保留原始字符串。
     *
     * @param jsonText 原始文本
     * @return JsonNode 或原始字符串
     */
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

    /**
     * 构造单键值 map，便于写事件 payload。
     *
     * @param k 键
     * @param v 值
     * @return 单项 map
     */
    private Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> map = new HashMap<>();
        map.put(k, v);
        return map;
    }
}

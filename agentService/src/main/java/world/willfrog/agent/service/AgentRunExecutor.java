package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import world.willfrog.agent.ai.PlanningAgent;
import world.willfrog.agent.ai.SummarizingAgent;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.agent.tool.MarketDataTools;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunExecutor {

    private final AgentRunMapper runMapper;
    private final AgentEventService eventService;
    private final PlanningAgent planningAgent;
    private final SummarizingAgent summarizingAgent;
    private final MarketDataTools marketDataTools;
    private final ObjectMapper objectMapper;

    @Async
    public void executeAsync(String runId) {
        try {
            execute(runId);
        } catch (Exception e) {
            log.error("Agent run execute failed: runId={}", runId, e);
        }
    }

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

            runMapper.updateStatus(runId, userId, AgentRunStatus.PLANNING);
            eventService.append(runId, userId, "PLANNING_STARTED", mapOf("run_id", runId));

            String userGoal = eventService.extractUserGoal(run.getExt());
            String planJson = planningAgent.plan(userGoal);
            runMapper.updatePlan(runId, userId, AgentRunStatus.EXECUTING, planJson);
            eventService.append(runId, userId, "PLAN_CREATED", mapOf("plan_json", planJson));

            String executionLog = executePlan(planJson);
            eventService.append(runId, userId, "EXECUTION_FINISHED", mapOf("log", executionLog));

            if (!eventService.isRunnable(runId, userId)) {
                return;
            }

            runMapper.updateStatus(runId, userId, AgentRunStatus.SUMMARIZING);
            eventService.append(runId, userId, "SUMMARIZING_STARTED", mapOf("run_id", runId));

            String summarizeInput = "UserGoal: " + userGoal + "\n\nPlan:\n" + planJson + "\n\nExecutionLog:\n" + executionLog;
            String answer = summarizingAgent.summarize(summarizeInput);

            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("user_goal", userGoal);
            snapshot.put("plan_json", safeJson(planJson));
            snapshot.put("execution_log", executionLog);
            snapshot.put("answer", answer);
            String snapshotJson = objectMapper.writeValueAsString(snapshot);

            runMapper.updateSnapshot(runId, userId, AgentRunStatus.COMPLETED, snapshotJson, true, null);
            eventService.append(runId, userId, "COMPLETED", mapOf("answer", answer));
        } catch (Exception e) {
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            runMapper.updateSnapshot(runId, userId, AgentRunStatus.FAILED, run.getSnapshotJson(), true, err);
            eventService.append(runId, userId, "FAILED", mapOf("error", err));
        }
    }

    private String executePlan(String planJson) {
        StringBuilder logBuilder = new StringBuilder();
        logBuilder.append("Simulated plan execution.\n");
        if (planJson == null || planJson.isBlank()) {
            return logBuilder.toString();
        }
        try {
            JsonNode root = objectMapper.readTree(planJson);
            JsonNode steps = root.get("steps");
            if (steps == null || !steps.isArray()) {
                logBuilder.append("No steps field.\n");
                return logBuilder.toString();
            }

            int toolCalls = 0;
            for (JsonNode step : steps) {
                String stepId = step.has("step_id") ? step.get("step_id").asText() : step.path("stepId").asText("");
                String desc = step.path("description").asText("");
                String toolName = step.path("tool_name").asText(step.path("toolName").asText(""));
                JsonNode paramsNode = step.get("parameters");
                logBuilder.append("- step ").append(stepId).append(": ").append(desc).append("\n");
                if (toolName == null || toolName.isBlank()) {
                    continue;
                }

                if (toolCalls >= 10) {
                    logBuilder.append("  tool calls reached limit, skip remaining.\n");
                    break;
                }

                Map<String, Object> params = jsonNodeToMap(paramsNode);
                String toolResult = invokeTool(toolName.trim(), params);
                toolCalls += 1;
                logBuilder.append("  tool ").append(toolName).append(" => ").append(toolResult).append("\n");
            }
        } catch (Exception e) {
            logBuilder.append("Plan parse/execute failed: ").append(e.getMessage()).append("\n");
        }
        return logBuilder.toString();
    }

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


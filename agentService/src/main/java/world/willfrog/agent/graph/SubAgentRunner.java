package world.willfrog.agent.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.tool.ToolRouter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubAgentRunner {

    private final ToolRouter toolRouter;
    private final ObjectMapper objectMapper;
    private final AgentEventService eventService;

    @Data
    @Builder
    public static class SubAgentRequest {
        private String runId;
        private String userId;
        private String taskId;
        private String goal;
        private String context;
        private Set<String> toolWhitelist;
        private int maxSteps;
    }

    @Data
    @Builder
    public static class SubAgentResult {
        private boolean success;
        private String answer;
        private String error;
        private List<Map<String, Object>> steps;
    }

    public SubAgentResult run(SubAgentRequest request, ChatLanguageModel model) {
        if (request == null || request.getGoal() == null || request.getGoal().isBlank()) {
            return SubAgentResult.builder().success(false).error("sub_agent goal missing").build();
        }
        String tools = String.join(", ", request.getToolWhitelist());
        String systemPrompt = "你是一个子任务代理。必须先输出线性计划 JSON，不超过 " + request.getMaxSteps() + " 步。" +
                "仅能使用下列工具名称：" + tools + "。" +
                "输出格式:\n" +
                "{\"steps\":[{\"tool\":\"name\",\"args\":{...},\"note\":\"why\"}],\"expected\":\"...\"}";

        List<Map<String, Object>> executedSteps = new ArrayList<>();
        try {
            Response<dev.langchain4j.data.message.AiMessage> planResp = model.generate(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage("目标: " + request.getGoal() + "\n上下文: " + (request.getContext() == null ? "" : request.getContext()))
            ));

            String planText = planResp.content().text();
            String json = extractJson(planText);
            JsonNode root = objectMapper.readTree(json);
            JsonNode stepsNode = root.path("steps");
            if (!stepsNode.isArray()) {
                emitEvent(request, "SUB_AGENT_FAILED", Map.of(
                        "task_id", nvl(request.getTaskId()),
                        "error", "sub_agent plan missing steps"
                ));
                return SubAgentResult.builder().success(false).error("sub_agent plan missing steps").build();
            }
            if (stepsNode.size() > request.getMaxSteps()) {
                emitEvent(request, "SUB_AGENT_FAILED", Map.of(
                        "task_id", nvl(request.getTaskId()),
                        "error", "sub_agent steps exceed max"
                ));
                return SubAgentResult.builder().success(false).error("sub_agent steps exceed max").build();
            }

            emitEvent(request, "SUB_AGENT_PLAN_CREATED", Map.of(
                    "task_id", nvl(request.getTaskId()),
                    "steps_count", stepsNode.size(),
                    "steps", buildStepSummary(stepsNode)
            ));

            for (JsonNode stepNode : stepsNode) {
                String tool = stepNode.path("tool").asText();
                if (!request.getToolWhitelist().contains(tool)) {
                    emitEvent(request, "SUB_AGENT_FAILED", Map.of(
                            "task_id", nvl(request.getTaskId()),
                            "error", "sub_agent tool not allowed: " + tool
                    ));
                    return SubAgentResult.builder().success(false).error("sub_agent tool not allowed: " + tool).build();
                }
                JsonNode argsNode = stepNode.path("args");
                Map<String, Object> args = argsNode.isObject() ? objectMapper.convertValue(argsNode, Map.class) : Map.of();

                int stepIndex = executedSteps.size();
                emitEvent(request, "SUB_AGENT_STEP_STARTED", Map.of(
                        "task_id", nvl(request.getTaskId()),
                        "step_index", stepIndex,
                        "tool", tool,
                        "args", args
                ));
                String output = toolRouter.invoke(tool, args);

                Map<String, Object> stepResult = new HashMap<>();
                stepResult.put("tool", tool);
                stepResult.put("args", args);
                stepResult.put("output", output);
                executedSteps.add(stepResult);

                emitEvent(request, "SUB_AGENT_STEP_FINISHED", Map.of(
                        "task_id", nvl(request.getTaskId()),
                        "step_index", stepIndex,
                        "tool", tool,
                        "success", !output.startsWith("Tool invocation error"),
                        "output_preview", preview(output)
                ));
            }

            String summaryPrompt = "请基于以下工具执行结果，给出简洁结论用于主流程合并：";
            Response<dev.langchain4j.data.message.AiMessage> finalResp = model.generate(List.of(
                    new SystemMessage(summaryPrompt),
                    new UserMessage("目标: " + request.getGoal() + "\n结果: " + objectMapper.writeValueAsString(executedSteps))
            ));

            emitEvent(request, "SUB_AGENT_COMPLETED", Map.of(
                    "task_id", nvl(request.getTaskId()),
                    "steps", executedSteps.size()
            ));

            return SubAgentResult.builder()
                    .success(true)
                    .answer(finalResp.content().text())
                    .steps(executedSteps)
                    .build();
        } catch (Exception e) {
            log.warn("Sub-agent failed", e);
            emitEvent(request, "SUB_AGENT_FAILED", Map.of(
                    "task_id", nvl(request.getTaskId()),
                    "error", e.getMessage()
            ));
            return SubAgentResult.builder().success(false).error(e.getMessage()).steps(executedSteps).build();
        }
    }

    private String extractJson(String text) {
        if (text == null) {
            return "{}";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text.trim();
    }

    private void emitEvent(SubAgentRequest request, String eventType, Object payload) {
        if (request == null || request.getRunId() == null || request.getRunId().isBlank()) {
            return;
        }
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            return;
        }
        eventService.append(request.getRunId(), request.getUserId(), eventType, payload);
    }

    private List<Map<String, Object>> buildStepSummary(JsonNode stepsNode) {
        List<Map<String, Object>> summary = new ArrayList<>();
        int idx = 0;
        for (JsonNode node : stepsNode) {
            Map<String, Object> item = new HashMap<>();
            item.put("index", idx++);
            item.put("tool", node.path("tool").asText());
            item.put("note", node.path("note").asText());
            summary.add(item);
        }
        return summary;
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > 300) {
            return text.substring(0, 300);
        }
        return text;
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}

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

    @Data
    @Builder
    public static class SubAgentRequest {
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
                return SubAgentResult.builder().success(false).error("sub_agent plan missing steps").build();
            }
            if (stepsNode.size() > request.getMaxSteps()) {
                return SubAgentResult.builder().success(false).error("sub_agent steps exceed max").build();
            }
            for (JsonNode stepNode : stepsNode) {
                String tool = stepNode.path("tool").asText();
                if (!request.getToolWhitelist().contains(tool)) {
                    return SubAgentResult.builder().success(false).error("sub_agent tool not allowed: " + tool).build();
                }
                JsonNode argsNode = stepNode.path("args");
                Map<String, Object> args = argsNode.isObject() ? objectMapper.convertValue(argsNode, Map.class) : Map.of();
                String output = toolRouter.invoke(tool, args);

                Map<String, Object> stepResult = new HashMap<>();
                stepResult.put("tool", tool);
                stepResult.put("args", args);
                stepResult.put("output", output);
                executedSteps.add(stepResult);
            }

            String summaryPrompt = "请基于以下工具执行结果，给出简洁结论用于主流程合并：";
            Response<dev.langchain4j.data.message.AiMessage> finalResp = model.generate(List.of(
                    new SystemMessage(summaryPrompt),
                    new UserMessage("目标: " + request.getGoal() + "\n结果: " + objectMapper.writeValueAsString(executedSteps))
            ));

            return SubAgentResult.builder()
                    .success(true)
                    .answer(finalResp.content().text())
                    .steps(executedSteps)
                    .build();
        } catch (Exception e) {
            log.warn("Sub-agent failed", e);
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
}

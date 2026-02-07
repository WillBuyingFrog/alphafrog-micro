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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
/**
 * 子代理执行器。
 * <p>
 * 子代理不直接输出最终回复，而是：
 * 1. 先生成线性步骤计划；
 * 2. 按步骤调用工具；
 * 3. 产出可供主流程合并的局部结论。
 */
public class SubAgentRunner {
    /** 子代理规划最大重试次数（用于修正无效工具/格式问题）。 */
    private static final int MAX_PLAN_ATTEMPTS = 3;

    /** 工具路由器。 */
    private final ToolRouter toolRouter;
    /** JSON 序列化/反序列化工具。 */
    private final ObjectMapper objectMapper;
    /** 事件服务（记录子代理过程）。 */
    private final AgentEventService eventService;

    /**
     * 子代理请求参数。
     */
    @Data
    @Builder
    public static class SubAgentRequest {
        /** run ID。 */
        private String runId;
        /** 用户 ID。 */
        private String userId;
        /** 对应的并行任务 ID。 */
        private String taskId;
        /** 子任务目标。 */
        private String goal;
        /** 上下文补充。 */
        private String context;
        /** 可用工具白名单。 */
        private Set<String> toolWhitelist;
        /** 允许的最大步骤数。 */
        private int maxSteps;
    }

    /**
     * 子代理执行结果。
     */
    @Data
    @Builder
    public static class SubAgentResult {
        /** 是否成功。 */
        private boolean success;
        /** 子代理结论文本。 */
        private String answer;
        /** 错误信息（失败时）。 */
        private String error;
        /** 逐步执行记录。 */
        private List<Map<String, Object>> steps;
    }

    /**
     * 执行子代理任务。
     *
     * @param request 子代理请求
     * @param model   聊天模型
     * @return 子代理执行结果
     */
    public SubAgentResult run(SubAgentRequest request, ChatLanguageModel model) {
        if (request == null || request.getGoal() == null || request.getGoal().isBlank()) {
            return SubAgentResult.builder().success(false).error("sub_agent goal missing").build();
        }
        Set<String> whitelist = request.getToolWhitelist() == null ? Collections.emptySet() : request.getToolWhitelist();
        String tools = whitelist.stream().sorted().collect(Collectors.joining(", "));
        String systemPrompt = buildPlannerPrompt(tools, request.getMaxSteps());

        List<Map<String, Object>> executedSteps = new ArrayList<>();
        try {
            // 第一步：生成“可执行的线性步骤 JSON”。若出现无效工具，自动要求模型重规划。
            JsonNode stepsNode = null;
            String lastPlanError = "sub_agent plan generation failed";
            String retryHint = "";
            for (int attempt = 1; attempt <= MAX_PLAN_ATTEMPTS; attempt++) {
                Response<dev.langchain4j.data.message.AiMessage> planResp = model.generate(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage("目标: " + request.getGoal()
                                + "\n上下文: " + (request.getContext() == null ? "" : request.getContext())
                                + "\n" + retryHint)
                ));
                String planText = planResp.content().text();
                String json = extractJson(planText);
                JsonNode root = objectMapper.readTree(json);
                JsonNode candidate = root.path("steps");

                if (!candidate.isArray()) {
                    lastPlanError = "sub_agent plan missing steps";
                    retryHint = "上一次输出缺少 steps 数组。请严格按 JSON 格式输出 steps。";
                    emitEvent(request, "SUB_AGENT_PLAN_RETRY", Map.of(
                            "task_id", nvl(request.getTaskId()),
                            "attempt", attempt,
                            "reason", lastPlanError
                    ));
                    continue;
                }
                if (candidate.size() > request.getMaxSteps()) {
                    lastPlanError = "sub_agent steps exceed max";
                    retryHint = "上一次步骤数超过上限。请将 steps 控制在 " + request.getMaxSteps() + " 步以内。";
                    emitEvent(request, "SUB_AGENT_PLAN_RETRY", Map.of(
                            "task_id", nvl(request.getTaskId()),
                            "attempt", attempt,
                            "reason", lastPlanError,
                            "steps_count", candidate.size()
                    ));
                    continue;
                }
                List<String> invalidTools = collectInvalidTools(candidate, whitelist);
                if (!invalidTools.isEmpty()) {
                    lastPlanError = "sub_agent tool not allowed: " + invalidTools.get(0);
                    retryHint = "上一次包含未允许的工具名: " + String.join(", ", invalidTools)
                            + "。禁止使用 sub_agent/workflow/tool 等伪工具名，只能使用允许列表中的真实工具。";
                    emitEvent(request, "SUB_AGENT_PLAN_RETRY", Map.of(
                            "task_id", nvl(request.getTaskId()),
                            "attempt", attempt,
                            "reason", "invalid_tool",
                            "invalid_tools", invalidTools
                    ));
                    continue;
                }
                stepsNode = candidate;
                break;
            }
            if (stepsNode == null) {
                emitEvent(request, "SUB_AGENT_FAILED", Map.of(
                        "task_id", nvl(request.getTaskId()),
                        "error", lastPlanError
                ));
                return SubAgentResult.builder().success(false).error(lastPlanError).build();
            }

            emitEvent(request, "SUB_AGENT_PLAN_CREATED", Map.of(
                    "task_id", nvl(request.getTaskId()),
                    "steps_count", stepsNode.size(),
                    "steps", buildStepSummary(stepsNode)
            ));

            // 第二步：按计划逐步执行工具。
            for (JsonNode stepNode : stepsNode) {
                String tool = stepNode.path("tool").asText();
                if (!whitelist.contains(tool)) {
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

            // 第三步：把步骤执行结果再总结成可供主流程合并的结论文本。
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
                    "error", nvl(e.getMessage())
            ));
            return SubAgentResult.builder().success(false).error(nvl(e.getMessage())).steps(executedSteps).build();
        }
    }

    /**
     * 构建子代理规划提示词。
     *
     * @param tools    可用工具列表（逗号拼接）
     * @param maxSteps 最大步骤数
     * @return 系统提示词
     */
    private String buildPlannerPrompt(String tools, int maxSteps) {
        return "你是一个子任务代理。必须先输出线性计划 JSON，不超过 " + maxSteps + " 步。"
                + "仅能使用下列工具名称：" + tools + "。"
                + "严禁把 sub_agent、workflow、tool、agent 作为步骤工具名。"
                + "输出格式:\n"
                + "{\"steps\":[{\"tool\":\"name\",\"args\":{...},\"note\":\"why\"}],\"expected\":\"...\"}";
    }

    /**
     * 收集计划中不在白名单的工具名。
     *
     * @param stepsNode      步骤数组
     * @param toolWhitelist  工具白名单
     * @return 非法工具名列表（去重后）
     */
    private List<String> collectInvalidTools(JsonNode stepsNode, Set<String> toolWhitelist) {
        List<String> invalid = new ArrayList<>();
        for (JsonNode node : stepsNode) {
            String tool = node.path("tool").asText();
            if (!toolWhitelist.contains(tool) && !invalid.contains(tool)) {
                invalid.add(tool);
            }
        }
        return invalid;
    }

    /**
     * 从文本中抽取最外层 JSON 片段。
     *
     * @param text 模型输出文本
     * @return JSON 文本
     */
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

    /**
     * 输出子代理事件（参数缺失时静默跳过，避免污染主流程）。
     *
     * @param request   子代理请求
     * @param eventType 事件类型
     * @param payload   事件负载
     */
    private void emitEvent(SubAgentRequest request, String eventType, Object payload) {
        if (request == null || request.getRunId() == null || request.getRunId().isBlank()) {
            return;
        }
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            return;
        }
        eventService.append(request.getRunId(), request.getUserId(), eventType, payload);
    }

    /**
     * 将步骤 JSON 生成简要摘要，供事件与前端展示使用。
     *
     * @param stepsNode 步骤数组节点
     * @return 步骤摘要
     */
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

    /**
     * 裁剪长文本，避免事件负载过大。
     *
     * @param text 原始文本
     * @return 预览文本
     */
    private String preview(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > 300) {
            return text.substring(0, 300);
        }
        return text;
    }

    /**
     * 空值转空字符串。
     *
     * @param value 原始字符串
     * @return 非空字符串
     */
    private String nvl(String value) {
        return value == null ? "" : value;
    }
}

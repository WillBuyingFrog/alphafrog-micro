package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.ReactConversationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class PlanJudge {

    private final AgentPromptService promptService;
    private final ObjectMapper objectMapper;

    /**
     * ReAct 累积模式：通过 ReactConversationContext 进行判断，共享 System Prompt 前缀，
     * 最大化 LLM provider 的 KV 缓存命中率。
     */
    public JudgeDecision judge(ReactConversationContext reactCtx,
                               TodoExecutionRecord record,
                               TodoPlan currentPlan,
                               Map<String, TodoExecutionRecord> context,
                               ChatModel model) {
        String stageInstruction = promptService.planJudgeStageInstruction();
        if (stageInstruction == null || stageInstruction.isBlank()) {
            log.debug("PlanJudge stage instruction is empty, defaulting to FAIL");
            return JudgeDecision.FAIL;
        }

        Map<String, Object> payload = buildJudgePayload(record, currentPlan, context, null);
        String userMessage = safeWrite(payload);

        String userContent = promptService.dynamicContextPrefix() + "\n"
                + stageInstruction + "\n" + userMessage;
        reactCtx.addUserMessage(userContent);

        try {
            ChatResponse response = model.chat(reactCtx.getMessages());
            String text = response.aiMessage() == null ? "" : nvl(response.aiMessage().text());
            reactCtx.addAssistantMessage(text);
            return parseDecision(text);
        } catch (Exception e) {
            log.warn("PlanJudge LLM call (ReAct) failed: {}", e.getMessage());
            return JudgeDecision.FAIL;
        }
    }

    /**
     * 独立调用模式（向后兼容）：使用独立的 System Prompt 调用 LLM。
     */
    public JudgeDecision judge(TodoExecutionRecord record,
                               TodoPlan currentPlan,
                               Map<String, TodoExecutionRecord> context,
                               String userGoal,
                               ChatModel model) {
        String systemPrompt = promptService.planJudgeRuntimeSystemPrompt();
        if (systemPrompt == null || systemPrompt.isBlank()) {
            log.debug("PlanJudge system prompt is empty, defaulting to FAIL");
            return JudgeDecision.FAIL;
        }

        Map<String, Object> payload = buildJudgePayload(record, currentPlan, context, userGoal);
        String userMessage = safeWrite(payload);

        List<ChatMessage> messages = List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(promptService.dynamicContextPrefix() + "\n" + userMessage)
        );

        try {
            ChatResponse response = model.chat(messages);
            String text = response.aiMessage() == null ? "" : nvl(response.aiMessage().text());
            return parseDecision(text);
        } catch (Exception e) {
            log.warn("PlanJudge LLM call failed: {}", e.getMessage());
            return JudgeDecision.FAIL;
        }
    }

    private Map<String, Object> buildJudgePayload(TodoExecutionRecord record,
                                                   TodoPlan currentPlan,
                                                   Map<String, TodoExecutionRecord> context,
                                                   String userGoal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (userGoal != null) {
            payload.put("user_goal", nvl(userGoal));
        }
        payload.put("failed_record", Map.of(
                "success", record.isSuccess(),
                "summary", nvl(record.getSummary()),
                "output_preview", preview(record.getOutput()),
                "failure_category", nvl(record.getFailureCategory())
        ));
        payload.put("current_plan_size", currentPlan.getItems() == null ? 0 : currentPlan.getItems().size());
        payload.put("completed_context_keys", context == null ? List.of() : context.keySet());
        return payload;
    }

    JudgeDecision parseDecision(String text) {
        if (text == null || text.isBlank()) {
            return JudgeDecision.FAIL;
        }
        String json = extractJsonFromResponse(text);
        if (json != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
                Object decision = parsed.get("decision");
                if (decision != null) {
                    return parseDecisionString(String.valueOf(decision));
                }
            } catch (Exception e) {
                log.debug("Failed to parse judge JSON response, trying raw text");
            }
        }
        return parseDecisionString(text.trim());
    }

    private JudgeDecision parseDecisionString(String raw) {
        String upper = nvl(raw).trim().toUpperCase();
        // ABORT 优先判断（避免被 FAIL 包含）
        if (upper.contains("ABORT")) {
            return JudgeDecision.ABORT;
        }
        if (upper.contains("PATCH_PLAN")) {
            return JudgeDecision.PATCH_PLAN;
        }
        if (upper.contains("FALLBACK_TO_LINEAR")) {
            return JudgeDecision.FALLBACK_TO_LINEAR;
        }
        if (upper.contains("CONTINUE_WITH_RECOVERY_PARAMS")) {
            return JudgeDecision.CONTINUE_WITH_RECOVERY_PARAMS;
        }
        if (upper.contains("CONTINUE")) {
            return JudgeDecision.CONTINUE_WITH_RECOVERY_PARAMS;
        }
        if (upper.contains("RETRY")) {
            return JudgeDecision.RETRY;
        }
        // 默认 FAIL（向后兼容）
        return JudgeDecision.FAIL;
    }

    private String extractJsonFromResponse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return null;
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private String safeWrite(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }
}

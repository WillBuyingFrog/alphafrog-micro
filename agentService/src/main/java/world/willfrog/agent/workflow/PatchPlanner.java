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
public class PatchPlanner {

    private final AgentPromptService promptService;
    private final ObjectMapper objectMapper;

    /**
     * ReAct 累积模式：通过 ReactConversationContext 生成补丁，共享 System Prompt 前缀，
     * 最大化 LLM provider 的 KV 缓存命中率。
     */
    public PlanPatch generatePatch(ReactConversationContext reactCtx,
                                   TodoExecutionRecord record,
                                   TodoPlan currentPlan,
                                   Map<String, TodoExecutionRecord> context,
                                   ChatModel model) {
        String stageInstruction = promptService.patchPlannerStageInstruction();
        if (stageInstruction == null || stageInstruction.isBlank()) {
            log.debug("PatchPlanner stage instruction is empty, returning null");
            return null;
        }

        Map<String, Object> payload = buildPatchPayload(record, currentPlan, context, null);
        String userMessage = safeWrite(payload);

        String userContent = promptService.dynamicContextPrefix() + "\n"
                + stageInstruction + "\n" + userMessage;
        reactCtx.addUserMessage(userContent);

        try {
            ChatResponse response = model.chat(reactCtx.getMessages());
            String text = response.aiMessage() == null ? "" : nvl(response.aiMessage().text());
            reactCtx.addAssistantMessage(text);
            return parsePatch(text);
        } catch (Exception e) {
            log.warn("PatchPlanner LLM call (ReAct) failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 独立调用模式（向后兼容）：使用独立的 System Prompt 调用 LLM。
     */
    public PlanPatch generatePatch(TodoExecutionRecord record,
                                   TodoPlan currentPlan,
                                   Map<String, TodoExecutionRecord> context,
                                   String userGoal,
                                   ChatModel model) {
        String systemPrompt = promptService.parallelPatchPlannerSystemPrompt();
        if (systemPrompt == null || systemPrompt.isBlank()) {
            log.debug("PatchPlanner system prompt is empty, returning null");
            return null;
        }

        Map<String, Object> payload = buildPatchPayload(record, currentPlan, context, userGoal);
        String userMessage = safeWrite(payload);

        List<ChatMessage> messages = List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(promptService.dynamicContextPrefix() + "\n" + userMessage)
        );

        try {
            ChatResponse response = model.chat(messages);
            String text = response.aiMessage() == null ? "" : nvl(response.aiMessage().text());
            return parsePatch(text);
        } catch (Exception e) {
            log.warn("PatchPlanner LLM call failed: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildPatchPayload(TodoExecutionRecord record,
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

        List<Map<String, Object>> planItems = new java.util.ArrayList<>();
        if (currentPlan.getItems() != null) {
            for (TodoItem item : currentPlan.getItems()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", nvl(item.getId()));
                row.put("sequence", item.getSequence());
                row.put("type", item.getType() == null ? "" : item.getType().name());
                row.put("toolName", nvl(item.getToolName()));
                row.put("status", item.getStatus() == null ? "" : item.getStatus().name());
                planItems.add(row);
            }
        }
        payload.put("current_plan", planItems);
        payload.put("completed_context_keys", context == null ? List.of() : context.keySet());
        return payload;
    }

    @SuppressWarnings("unchecked")
    PlanPatch parsePatch(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String json = extractJsonFromResponse(text);
        if (json == null) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            String patchTypeStr = nvl((String) parsed.get("patchType"));
            PatchType patchType = parsePatchType(patchTypeStr);
            if (patchType == null) {
                log.warn("PatchPlanner: invalid patchType '{}'", patchTypeStr);
                return null;
            }

            String targetTodoId = nvl((String) parsed.get("targetTodoId"));
            String reason = nvl((String) parsed.get("reason"));

            Map<String, Object> patchData = new LinkedHashMap<>();
            Object patchDataObj = parsed.get("patchData");
            if (patchDataObj instanceof Map<?, ?> patchDataMap) {
                for (Map.Entry<?, ?> entry : patchDataMap.entrySet()) {
                    patchData.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }

            return PlanPatch.builder()
                    .patchType(patchType)
                    .targetTodoId(targetTodoId)
                    .patchData(patchData)
                    .reason(reason)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse patch response: {}", e.getMessage());
            return null;
        }
    }

    private PatchType parsePatchType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return PatchType.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
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

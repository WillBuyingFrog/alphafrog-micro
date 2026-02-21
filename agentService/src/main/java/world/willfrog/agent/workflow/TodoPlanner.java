package world.willfrog.agent.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.service.AgentLlmRequestSnapshotBuilder;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentMessageService;
import world.willfrog.agent.service.AgentContextCompressor;
import world.willfrog.agent.entity.AgentRunMessage;
import world.willfrog.agent.service.AgentRunStateStore;
import world.willfrog.agent.context.AgentContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class TodoPlanner {

    private final AgentPromptService promptService;
    private final AgentEventService eventService;
    private final AgentRunMapper runMapper;
    private final AgentRunStateStore stateStore;
    private final AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    private final AgentObservabilityService observabilityService;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final AgentLlmProperties llmProperties;
    private final AgentMessageService messageService;
    private final AgentContextCompressor contextCompressor;
    private final ObjectMapper objectMapper;

    @Value("${agent.flow.workflow.max-todos:10}")
    private int defaultMaxTodos;

    public TodoPlan plan(PlanRequest request) {
        AgentRun run = request.getRun();
        String runId = run.getId();
        String userId = request.getUserId();

        eventService.append(runId, userId, "PLANNING_STARTED", Map.of("run_id", runId));

        try {
            boolean override = stateStore.isPlanOverride(runId);
            TodoPlan todoPlan = null;
            Optional<String> stateStoredPlan = stateStore.loadPlan(runId);
            if (stateStoredPlan.isPresent()) {
                todoPlan = parsePlan(stateStoredPlan.get());
            } else if (run.getPlanJson() != null && !run.getPlanJson().isBlank() && !"{}".equals(run.getPlanJson().trim())) {
                todoPlan = parsePlan(run.getPlanJson());
            }

            Set<String> toolWhitelist = request.getToolSpecifications().stream()
                    .map(ToolSpecification::name)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            if (todoPlan == null || todoPlan.getItems().isEmpty()) {
                todoPlan = generatePlan(request, toolWhitelist);
            }

            todoPlan = normalize(todoPlan, resolveMaxTodos(), toolWhitelist);
            if (todoPlan.getItems().isEmpty()) {
                throw new IllegalStateException("todo_plan_empty");
            }

            String planJson = safeWrite(todoPlan);
            runMapper.updatePlan(runId, userId, AgentRunStatus.EXECUTING, planJson);
            stateStore.recordPlan(runId, planJson, true);
            if (override) {
                stateStore.clearPlanOverride(runId);
            }

            eventService.append(runId, userId, "TODO_LIST_CREATED", Map.of(
                    "items_count", todoPlan.getItems().size(),
                    "plan", planJson
            ));
            eventService.append(runId, userId, "PLANNING_COMPLETED", Map.of(
                    "items_count", todoPlan.getItems().size(),
                    "itemsCount", todoPlan.getItems().size(),
                    "endpoint", nvl(request.getEndpointName()),
                    "model", nvl(request.getModelName())
            ));
            return todoPlan;
        } catch (Exception e) {
            String reason = nvl(e.getMessage()).isBlank() ? e.getClass().getSimpleName() : nvl(e.getMessage());
            eventService.append(runId, userId, "PLANNING_FAILED", Map.of("error", reason));
            throw new IllegalStateException(reason, e);
        }
    }

    private TodoPlan generatePlan(PlanRequest request, Set<String> toolWhitelist) {
        String runId = request.getRun().getId();
        String toolList = toolWhitelist.stream().sorted().collect(Collectors.joining(", "));
        String prompt = promptService.todoPlannerSystemPrompt(toolList, resolveMaxTodos());

        // 加载消息历史（多轮对话支持）
        String dialogueContext = buildDialogueContext(runId, request.getUserGoal());
        String userMessageContent;
        if (dialogueContext.isBlank()) {
            userMessageContent = request.getUserGoal();
        } else {
            userMessageContent = "历史对话压缩内容：\n" + dialogueContext
                    + "\n\n当前轮次用户需求：" + request.getUserGoal()
                    + "\n\n请参考历史对话，以当前轮次用户需求为重点规划。";
        }

        List<ChatMessage> messages = List.of(
                new SystemMessage(prompt),
                new UserMessage(userMessageContent)
        );

        // 设置当前 phase/stage 并记录开始时间
        String previousStage = AgentContext.getStage();
        AgentContext.setPhase(AgentObservabilityService.PHASE_PLANNING);
        AgentContext.setStage("todo_planning");
        long llmStartedAt = System.currentTimeMillis();
        Response<AiMessage> response;
        try {
            response = request.getModel().generate(messages);
        } finally {
            if (previousStage == null || previousStage.isBlank()) {
                AgentContext.clearStage();
            } else {
                AgentContext.setStage(previousStage);
            }
        }
        long llmCompletedAt = System.currentTimeMillis();
        long llmDurationMs = llmCompletedAt - llmStartedAt;
        String raw = response.content() == null ? "" : nvl(response.content().text());

        Map<String, Object> llmRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                request.getEndpointName(),
                request.getEndpointBaseUrl(),
                request.getModelName(),
                messages,
                request.getToolSpecifications(),
                Map.of("stage", "todo_planning")
        );
        String planningTraceId = observabilityService.recordLlmCall(
                request.getRun().getId(),
                AgentObservabilityService.PHASE_PLANNING,
                response.tokenUsage(),
                llmDurationMs,
                llmStartedAt,
                llmCompletedAt,
                request.getEndpointName(),
                request.getModelName(),
                null,
                llmRequestSnapshot,
                raw
        );

        TodoPlan todoPlan = parsePlan(extractJson(raw));
        String excerpt = raw.length() > 1000 ? raw.substring(0, 1000) : raw;
        for (TodoItem item : todoPlan.getItems() == null ? List.<TodoItem>of() : todoPlan.getItems()) {
            item.setDecisionLlmTraceId(planningTraceId);
            item.setDecisionStage("todo_planning");
            item.setDecisionExcerpt(excerpt);
        }
        return todoPlan;
    }

    private TodoPlan normalize(TodoPlan source, int maxTodos, Set<String> toolWhitelist) {
        TodoPlan out = new TodoPlan();
        out.setAnalysis(nvl(source.getAnalysis()));
        List<TodoItem> normalized = new ArrayList<>();
        int seq = 1;
        for (TodoItem raw : source.getItems() == null ? List.<TodoItem>of() : source.getItems()) {
            if (raw == null) {
                continue;
            }
            if (normalized.size() >= maxTodos) {
                break;
            }
            TodoType type = raw.getType() == null ? TodoType.TOOL_CALL : raw.getType();
            String id = nvl(raw.getId());
            if (id.isBlank()) {
                id = "todo_" + seq;
            }

            if (type == TodoType.TOOL_CALL && !toolWhitelist.contains(nvl(raw.getToolName()))) {
                throw new IllegalArgumentException("tool_not_allowed:" + nvl(raw.getToolName()));
            }

            TodoItem item = TodoItem.builder()
                    .id(id)
                    .sequence(seq)
                    .type(type)
                    .toolName(nvl(raw.getToolName()))
                    .params(raw.getParams() == null ? Map.of() : raw.getParams())
                    .reasoning(nvl(raw.getReasoning()))
                    .executionMode(raw.getExecutionMode() == null ? ExecutionMode.AUTO : raw.getExecutionMode())
                    .status(TodoStatus.PENDING)
                    .decisionLlmTraceId(nvl(raw.getDecisionLlmTraceId()))
                    .decisionStage(nvl(raw.getDecisionStage()))
                    .decisionExcerpt(nvl(raw.getDecisionExcerpt()))
                    .createdAt(Instant.now())
                    .build();
            normalized.add(item);
            seq++;
        }

        if (normalized.isEmpty()) {
            normalized.add(TodoItem.builder()
                    .id("todo_1")
                    .sequence(1)
                    .type(TodoType.SUB_AGENT)
                    .toolName("executePython")
                    .params(Map.of())
                    .reasoning("请按用户目标完成任务")
                    .executionMode(ExecutionMode.FORCE_SUB_AGENT)
                    .status(TodoStatus.PENDING)
                    .decisionLlmTraceId("")
                    .decisionStage("")
                    .decisionExcerpt("")
                    .createdAt(Instant.now())
                    .build());
        }

        out.setItems(normalized);
        return out;
    }

    private TodoPlan parsePlan(String planJson) {
        if (planJson == null || planJson.isBlank()) {
            return TodoPlan.builder().analysis("").items(List.of()).build();
        }
        try {
            JsonNode root = objectMapper.readTree(planJson);
            JsonNode itemsNode = root.path("items");
            if (!itemsNode.isArray()) {
                itemsNode = root.path("todos");
            }

            List<TodoItem> items = new ArrayList<>();
            if (itemsNode.isArray()) {
                int seq = 1;
                for (JsonNode node : itemsNode) {
                    TodoItem item = TodoItem.builder()
                            .id(nvl(node.path("id").asText("")))
                            .sequence(node.path("sequence").asInt(seq))
                            .type(parseType(node.path("type").asText("TOOL_CALL")))
                            .toolName(nvl(node.path("toolName").asText("")))
                            .params(toMap(node.path("params")))
                            .reasoning(nvl(node.path("reasoning").asText("")))
                            .executionMode(parseExecutionMode(node.path("executionMode").asText("AUTO")))
                            .decisionLlmTraceId(nvl(node.path("decisionLlmTraceId").asText("")))
                            .decisionStage(nvl(node.path("decisionStage").asText("")))
                            .decisionExcerpt(nvl(node.path("decisionExcerpt").asText("")))
                            .status(TodoStatus.PENDING)
                            .build();
                    items.add(item);
                    seq++;
                }
            }

            return TodoPlan.builder()
                    .analysis(nvl(root.path("analysis").asText("")))
                    .items(items)
                    .build();
        } catch (Exception e) {
            return TodoPlan.builder().analysis("").items(List.of()).build();
        }
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    private TodoType parseType(String text) {
        try {
            return TodoType.valueOf(nvl(text).trim().toUpperCase());
        } catch (Exception e) {
            return TodoType.TOOL_CALL;
        }
    }

    private ExecutionMode parseExecutionMode(String text) {
        try {
            return ExecutionMode.valueOf(nvl(text).trim().toUpperCase());
        } catch (Exception e) {
            return ExecutionMode.AUTO;
        }
    }

    private int resolveMaxTodos() {
        int local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getMaxTodos)
                .orElse(0);
        if (local > 0) {
            return clamp(local, 1, 50);
        }
        int base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getMaxTodos)
                .orElse(0);
        if (base > 0) {
            return clamp(base, 1, 50);
        }
        return clamp(defaultMaxTodos, 1, 50);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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

    private String safeWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    /**
     * 构建对话上下文（用于多轮对话）。
     * <p>
     * 1. 加载消息历史
     * 2. 应用上下文压缩
     * 3. 格式化为对话文本
     *
     * @param runId Run ID
     * @return 对话上下文文本（空字符串表示没有历史消息）
     */
    private String buildDialogueContext(String runId, String currentUserGoal) {
        try {
            List<AgentRunMessage> messages = messageService.listMessages(runId);
            if (messages == null || messages.isEmpty()) {
                return "";
            }

            AgentContextCompressor.ContextBuildResult result = contextCompressor.buildCompressedContext(messages, currentUserGoal);
            return result.text();
        } catch (Exception e) {
            log.warn("Failed to build dialogue context for runId={}, ignoring: {}", runId, e.getMessage());
            return "";
        }
    }

    @Data
    @Builder
    public static class PlanRequest {
        private AgentRun run;
        private String userId;
        private String userGoal;
        private ChatLanguageModel model;
        private List<ToolSpecification> toolSpecifications;
        private String endpointName;
        private String endpointBaseUrl;
        private String modelName;
    }
}

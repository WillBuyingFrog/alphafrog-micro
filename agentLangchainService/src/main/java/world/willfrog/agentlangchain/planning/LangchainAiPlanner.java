package world.willfrog.agentlangchain.planning;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agent.workflow.TodoStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LangchainAiPlanner {

    private static final int DEFAULT_MAX_TODOS = 10;
    private static final String TODO_PLAN_SCHEMA_NAME = "todo_plan";

    private final AgentPromptService promptService;
    private final LangchainPlanningStructuredOutputSettings structuredOutputSettings;

    public LangchainTodoPlan plan(LangchainPlanningRequest request) {
        validate(request);
        int maxTodos = resolveMaxTodos(request.getMaxTodos());
        PlanExecutionMode mode = request.getExecutionMode() == null
                ? PlanExecutionMode.LINEAR
                : request.getExecutionMode();
        String toolList = buildToolList(request.getToolSpecifications());

        LangchainPlannerAiService service = AiServices.builder(LangchainPlannerAiService.class)
                .chatModel(request.getModel())
                .systemMessageProvider(ignored -> promptService.todoPlannerSystemPrompt(toolList, maxTodos))
                .build();

        AgentContext.StructuredOutputSpec previousSpec = AgentContext.getStructuredOutputSpec();
        String previousStage = AgentContext.getStage();
        AgentContext.setPhase(AgentObservabilityService.PHASE_PLANNING);
        AgentContext.setStage("planning_todos");
        boolean structuredEnabled = structuredOutputSettings.structuredEnabled();
        logPlanningSelection(request, structuredEnabled);
        try {
            if (structuredEnabled) {
                AgentContext.setStructuredOutputSpec(new AgentContext.StructuredOutputSpec(
                        TODO_PLAN_SCHEMA_NAME,
                        structuredOutputSettings.structuredStrict(),
                        structuredOutputSettings.todoPlanningJsonSchema(),
                        structuredOutputSettings.requireProviderParameters(),
                        structuredOutputSettings.allowProviderFallbacks()
                ));
            } else {
                AgentContext.clearStructuredOutputSpec();
            }
            LangchainTodoPlanResponse response = service.plan(
                    promptService.dynamicContextPrefix() + "\n" + nvl(request.getUserGoal()),
                    nvl(request.getDialogueContext()),
                    toolList,
                    mode.name(),
                    maxTodos
            );
            return normalize(response, maxTodos, mode);
        } finally {
            if (previousSpec == null) {
                AgentContext.clearStructuredOutputSpec();
            } else {
                AgentContext.setStructuredOutputSpec(previousSpec);
            }
            if (previousStage == null || previousStage.isBlank()) {
                AgentContext.clearStage();
            } else {
                AgentContext.setStage(previousStage);
            }
        }
    }

    private void logPlanningSelection(LangchainPlanningRequest request, boolean structuredEnabled) {
        log.info(
                "[LangchainAiPlanner] runId={} planningModel={} planningEndpoint={} providerOrder={} "
                        + "structuredOutput.enabled={} strict={} requireProviderParameters={} allowProviderFallbacks={} schemaName={}",
                nvl(request.getRunId()),
                blankToLog(request.getPlanningModelName()),
                blankToLog(request.getPlanningEndpointName()),
                request.getPlanningProviderOrder() == null ? "[]" : request.getPlanningProviderOrder(),
                structuredEnabled,
                structuredOutputSettings.structuredStrict(),
                structuredOutputSettings.requireProviderParameters(),
                structuredOutputSettings.allowProviderFallbacks(),
                structuredEnabled ? TODO_PLAN_SCHEMA_NAME : ""
        );
    }

    private String blankToLog(String value) {
        return isBlank(value) ? "-" : value.trim();
    }

    private void validate(LangchainPlanningRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("planning_request_required");
        }
        if (request.getModel() == null) {
            throw new IllegalArgumentException("planning_chat_model_required");
        }
        if (isBlank(request.getUserGoal())) {
            throw new IllegalArgumentException("planning_user_goal_required");
        }
    }

    private LangchainTodoPlan normalize(LangchainTodoPlanResponse response,
                                        int maxTodos,
                                        PlanExecutionMode mode) {
        if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
            throw new IllegalStateException("todo_plan_empty");
        }
        List<TodoItem> items = new ArrayList<>();
        int sequence = 1;
        for (LangchainTodoPlanResponse.TodoItemResponse itemResponse : response.getItems()) {
            if (itemResponse == null || isBlank(itemResponse.getDescription())) {
                continue;
            }
            if (items.size() >= maxTodos) {
                break;
            }
            int effectiveSequence = itemResponse.getSequence() != null && itemResponse.getSequence() > 0
                    ? itemResponse.getSequence()
                    : sequence;
            String id = nvl(itemResponse.getId()).trim();
            if (id.isBlank()) {
                id = "todo_" + effectiveSequence;
            }
            TodoItem item = TodoItem.builder()
                    .id(id)
                    .sequence(effectiveSequence)
                    .description(itemResponse.getDescription().trim())
                    .dependsOn(sanitizeList(itemResponse.getDependsOn()))
                    .groupKey(blankToNull(itemResponse.getGroupKey()))
                    .parallelizable(Boolean.TRUE.equals(itemResponse.getParallelizable()))
                    .status(TodoStatus.PENDING)
                    .createdAt(Instant.now())
                    .build();
            items.add(item);
            sequence++;
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("todo_plan_empty");
        }
        return LangchainTodoPlan.builder()
                .analysis(nvl(response.getAnalysis()))
                .items(items)
                .extractedEntities(sanitizeList(response.getExtractedEntities()))
                .executionMode(mode)
                .build();
    }

    private int resolveMaxTodos(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_MAX_TODOS;
        }
        return Math.max(1, Math.min(requested, 50));
    }

    private String buildToolList(List<ToolSpecification> specifications) {
        if (specifications == null || specifications.isEmpty()) {
            return "none";
        }
        return specifications.stream()
                .map(ToolSpecification::name)
                .filter(name -> !isBlank(name))
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private List<String> sanitizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = nvl(value).trim();
            if (!normalized.isBlank()) {
                unique.add(normalized);
            }
        }
        return List.copyOf(unique);
    }

    private String blankToNull(String value) {
        String normalized = nvl(value).trim();
        return normalized.isBlank() ? null : normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}

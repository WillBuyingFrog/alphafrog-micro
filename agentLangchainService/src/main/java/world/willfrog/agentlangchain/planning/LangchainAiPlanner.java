package world.willfrog.agentlangchain.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.service.ReactConversationContext;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.StructuredPlanningSupport;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Legacy-aligned two-stage planning ({@code reactSystemPrompt} + strategy/todos stages).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LangchainAiPlanner {

    private static final String STRATEGY_SCHEMA_NAME = "overall_plan";
    private static final String TODO_PLAN_SCHEMA_NAME = "todo_plan";
    private static final int DEFAULT_MAX_TODOS = 10;
    private static final int DEFAULT_MAX_ATTEMPTS = 2;

    private final AgentPromptService promptService;
    private final LangchainPlanningStructuredOutputSettings structuredOutputSettings;
    private final ObjectMapper objectMapper;

    public LangchainTodoPlan plan(LangchainPlanningRequest request) {
        validate(request);
        int maxTodos = resolveMaxTodos(request.getMaxTodos());
        PlanExecutionMode mode = request.getExecutionMode() == null
                ? PlanExecutionMode.LINEAR
                : request.getExecutionMode();
        String toolList = buildToolList(request.getToolSpecifications());
        Set<String> toolWhitelist = buildToolWhitelist(request.getToolSpecifications());

        AgentContext.setPhase(AgentObservabilityService.PHASE_PLANNING);
        String previousStage = AgentContext.getStage();
        AgentContext.StructuredOutputSpec previousSpec = AgentContext.getStructuredOutputSpec();
        try {
            if (structuredOutputSettings.strategyStageEnabled()) {
                return planTwoStageStructured(request, mode, maxTodos, toolList, toolWhitelist);
            }
            return planSingleStageLegacyTemplate(request, mode, maxTodos, toolList, toolWhitelist);
        } finally {
            restoreStructuredOutputSpec(previousSpec);
            restoreStage(previousStage);
        }
    }

    private LangchainTodoPlan planTwoStageStructured(LangchainPlanningRequest request,
                                                     PlanExecutionMode mode,
                                                     int maxTodos,
                                                     String toolList,
                                                     Set<String> toolWhitelist) {
        int maxAttempts = resolvePlanningMaxAttempts();
        int maxDetailLength = structuredOutputSettings.strategyMaxDetailLength();
        boolean structuredEnabled = structuredOutputSettings.structuredEnabled();
        String dialogueContext = nvl(request.getDialogueContext());
        String reactSystem = promptService.reactSystemPrompt();
        String dynamicPrefix = promptService.dynamicContextPrefix();
        StructuredPlanningSupport.StructuredPlanningException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ReactConversationContext ctx = new ReactConversationContext();
            ctx.setSystemMessage(reactSystem);
            try {
                String strategyStage = promptService.planningStrategyStageInstruction(toolList, maxTodos, maxDetailLength);
                String strategyContent = dialogueContext.isBlank()
                        ? dynamicPrefix + "\n" + strategyStage + "\n\n用户需求：" + request.getUserGoal()
                        : dynamicPrefix + "\n" + strategyStage + "\n\n历史对话压缩内容：\n" + dialogueContext
                        + "\n\n当前轮次用户需求：" + request.getUserGoal();
                ctx.addUserMessage(strategyContent);

                AgentContext.setStage("planning_strategy");
                applyStructuredSpec(
                        structuredEnabled,
                        STRATEGY_SCHEMA_NAME,
                        structuredOutputSettings.structuredStrict(),
                        StructuredPlanningSupport.strategyStageJsonSchema(maxDetailLength),
                        request
                );
                ChatResponse strategyResponse = request.getModel().chat(ctx.getMessages());
                String strategyRaw = strategyResponse.aiMessage() == null ? "" : nvl(strategyResponse.aiMessage().text());
                JsonNode strategyRoot = StructuredPlanningSupport.parseStructuredJson(objectMapper, strategyRaw);
                StructuredPlanningSupport.ValidationResultWithData<StructuredPlanningSupport.OverallPlan> strategyValidation =
                        StructuredPlanningSupport.validateStrategyStage(strategyRoot, maxDetailLength);
                if (!strategyValidation.valid()) {
                    throw new StructuredPlanningSupport.StructuredPlanningException(
                            strategyValidation.category(), strategyValidation.message());
                }
                StructuredPlanningSupport.OverallPlan overallPlan = strategyValidation.data();
                ctx.addAssistantMessage(strategyRaw);

                String todosStage = promptService.planningTodosStageInstruction(
                        overallPlan.mode(), overallPlan.detail(), toolList, maxTodos);
                ctx.addUserMessage(todosStage);

                AgentContext.setStage("planning_todos");
                applyStructuredSpec(
                        structuredEnabled,
                        TODO_PLAN_SCHEMA_NAME,
                        structuredOutputSettings.structuredStrict(),
                        structuredOutputSettings.todoPlanningJsonSchema(),
                        request
                );
                ChatResponse todosResponse = request.getModel().chat(ctx.getMessages());
                String todosRaw = todosResponse.aiMessage() == null ? "" : nvl(todosResponse.aiMessage().text());
                JsonNode todosRoot = StructuredPlanningSupport.parseStructuredJson(objectMapper, todosRaw);
                StructuredPlanningSupport.ValidationResult todosValidation =
                        StructuredPlanningSupport.validateTodoPlan(todosRoot, maxTodos, toolWhitelist);
                if (!todosValidation.valid()) {
                    throw new StructuredPlanningSupport.StructuredPlanningException(
                            todosValidation.category(), todosValidation.message());
                }
                LangchainTodoPlan plan = LangchainTodoPlanParser.fromJsonRoot(todosRoot, mode, maxTodos);
                if (overallPlan.detail() != null && !overallPlan.detail().isBlank()) {
                    plan = LangchainTodoPlan.builder()
                            .analysis(overallPlan.detail())
                            .items(plan.getItems())
                            .extractedEntities(plan.getExtractedEntities())
                            .executionMode(plan.getExecutionMode())
                            .build();
                }
                log.info(
                        "[LangchainAiPlanner] runId={} two_stage_planning ok attempt={} todos={}",
                        nvl(request.getRunId()),
                        attempt,
                        plan.getItems() == null ? 0 : plan.getItems().size()
                );
                return plan;
            } catch (StructuredPlanningSupport.StructuredPlanningException e) {
                lastError = e;
                log.warn(
                        "[LangchainAiPlanner] runId={} planning attempt {} failed: {} {}",
                        nvl(request.getRunId()),
                        attempt,
                        e.category(),
                        e.getMessage()
                );
            } finally {
                AgentContext.clearStructuredOutputSpec();
            }
        }
        throw new IllegalStateException("planning_retry_exhausted:"
                + (lastError == null ? "unknown" : lastError.category() + ":" + lastError.getMessage()));
    }

    private LangchainTodoPlan planSingleStageLegacyTemplate(LangchainPlanningRequest request,
                                                            PlanExecutionMode mode,
                                                            int maxTodos,
                                                            String toolList,
                                                            Set<String> toolWhitelist) {
        LangchainPlannerAiService service = dev.langchain4j.service.AiServices.builder(LangchainPlannerAiService.class)
                .chatModel(request.getModel())
                .systemMessageProvider(ignored -> promptService.reactSystemPrompt())
                .build();
        AgentContext.setStage("planning_todos");
        boolean structuredEnabled = structuredOutputSettings.structuredEnabled();
        try {
            if (structuredEnabled) {
                AgentContext.setStructuredOutputSpec(new AgentContext.StructuredOutputSpec(
                        TODO_PLAN_SCHEMA_NAME,
                        structuredOutputSettings.structuredStrict(),
                        structuredOutputSettings.todoPlanningJsonSchema(),
                        structuredOutputSettings.requireProviderParameters(request.getPlanningEndpointName()),
                        structuredOutputSettings.allowProviderFallbacks()
                ));
            }
            String dialogueCtx = nvl(request.getDialogueContext());
            String userMessage;
            if (dialogueCtx.isBlank()) {
                userMessage = promptService.dynamicContextPrefix() + "\n"
                        + promptService.planningTodosStageInstruction(mode.name(), "", toolList, maxTodos)
                        + "\n\n用户需求：" + request.getUserGoal();
            } else {
                userMessage = promptService.dynamicContextPrefix() + "\n"
                        + promptService.planningTodosStageInstruction(mode.name(), "", toolList, maxTodos)
                        + "\n\n历史对话压缩内容：\n" + dialogueCtx
                        + "\n\n当前轮次用户需求：" + request.getUserGoal();
            }
            LangchainTodoPlanResponse response = service.plan(userMessage);
            return normalizeResponse(response, maxTodos, mode);
        } finally {
            AgentContext.clearStructuredOutputSpec();
        }
    }

    private void applyStructuredSpec(boolean structuredEnabled,
                                     String schemaName,
                                     boolean strict,
                                     java.util.Map<String, Object> schema,
                                     LangchainPlanningRequest request) {
        if (!structuredEnabled) {
            AgentContext.clearStructuredOutputSpec();
            return;
        }
        AgentContext.setStructuredOutputSpec(new AgentContext.StructuredOutputSpec(
                schemaName,
                strict,
                schema,
                structuredOutputSettings.requireProviderParameters(request.getPlanningEndpointName()),
                structuredOutputSettings.allowProviderFallbacks()
        ));
    }

    private LangchainTodoPlan normalizeResponse(LangchainTodoPlanResponse response, int maxTodos, PlanExecutionMode mode) {
        if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
            throw new IllegalStateException("todo_plan_empty");
        }
        java.util.List<world.willfrog.agent.workflow.TodoItem> items = new java.util.ArrayList<>();
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
            items.add(world.willfrog.agent.workflow.TodoItem.builder()
                    .id(id)
                    .sequence(effectiveSequence)
                    .description(itemResponse.getDescription().trim())
                    .dependsOn(sanitizeList(itemResponse.getDependsOn()))
                    .groupKey(blankToNull(itemResponse.getGroupKey()))
                    .parallelizable(Boolean.TRUE.equals(itemResponse.getParallelizable()))
                    .status(world.willfrog.agent.workflow.TodoStatus.PENDING)
                    .createdAt(java.time.Instant.now())
                    .build());
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
        int configured = structuredOutputSettings.resolveMaxTodos(DEFAULT_MAX_TODOS);
        if (requested == null || requested <= 0) {
            return configured;
        }
        return Math.max(1, Math.min(requested, configured));
    }

    private int resolvePlanningMaxAttempts() {
        return DEFAULT_MAX_ATTEMPTS;
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

    private String buildToolList(java.util.List<ToolSpecification> specifications) {
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

    private Set<String> buildToolWhitelist(java.util.List<ToolSpecification> specifications) {
        if (specifications == null || specifications.isEmpty()) {
            return Set.of();
        }
        return specifications.stream()
                .map(ToolSpecification::name)
                .filter(name -> !isBlank(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private java.util.List<String> sanitizeList(java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return java.util.List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = nvl(value).trim();
            if (!normalized.isBlank()) {
                unique.add(normalized);
            }
        }
        return java.util.List.copyOf(unique);
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

    private void restoreStructuredOutputSpec(AgentContext.StructuredOutputSpec previousSpec) {
        if (previousSpec == null) {
            AgentContext.clearStructuredOutputSpec();
        } else {
            AgentContext.setStructuredOutputSpec(previousSpec);
        }
    }

    private void restoreStage(String previousStage) {
        if (previousStage == null || previousStage.isBlank()) {
            AgentContext.clearStage();
        } else {
            AgentContext.setStage(previousStage);
        }
    }
}

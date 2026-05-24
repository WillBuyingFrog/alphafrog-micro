package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.workflow.DatasetRefRegistry;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.tools.LangchainDatasetRefContext;
import world.willfrog.agentlangchain.tools.LangchainRepeatedToolCallContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
public class LangchainTodoNodeExecutor {

    private static final int DEFAULT_MAX_TOOL_ROUND_TRIPS = 8;

    private final AgentPromptService promptService;
    private final ObjectProvider<ToolProvider> toolProvider;
    private final LangchainRunExecutionGuard executionGuard;

    public LangchainTodoNodeResult execute(LangchainLinearWorkflowRequest request,
                                           TodoItem item,
                                           List<LangchainCompletedTodo> completedTodos,
                                           Map<String, String> datasetRefs,
                                           AtomicInteger toolCalls) {
        if (item == null) {
            return LangchainTodoNodeResult.failure("todo_item_required");
        }
        AgentContext.setTodoContext(item.getId(), item.getSequence());
        String userMessage = LangchainTodoUserMessageBuilder.buildTodoUserMessage(
                promptService,
                request.getUserGoal(),
                completedTodos,
                datasetRefs,
                item.getDescription(),
                request.getToolSpecifications());
        int callsBefore = toolCalls.get();
        LangchainDatasetRefContext.set(datasetRefs);
        LangchainRepeatedToolCallContext.clear();
        try {
            ensureRunnable(request);
            String output = buildTodoAiService(request, toolCalls, datasetRefs).execute(userMessage);
            if (isBlank(output)) {
                return LangchainTodoNodeResult.failure("empty_todo_output:" + item.getId());
            }
            String trimmed = output.trim();
            DatasetRefRegistry.registerFromJson(trimmed, datasetRefs);
            return LangchainTodoNodeResult.success(trimmed, Math.max(0, toolCalls.get() - callsBefore));
        } catch (Exception e) {
            return LangchainTodoNodeResult.failure(e.getMessage());
        } finally {
            LangchainRepeatedToolCallContext.clear();
            LangchainDatasetRefContext.clear();
            AgentContext.clearTodoContext();
        }
    }

    public String writeFinalAnswer(LangchainLinearWorkflowRequest request,
                                   List<LangchainCompletedTodo> completedTodos) {
        ensureRunnable(request);
        return buildFinalAnswerAiService(request)
                .answer(LangchainTodoUserMessageBuilder.buildFinalUserMessage(
                        promptService,
                        request.getUserGoal(),
                        completedTodos));
    }

    private LangchainTodoExecutionAiService buildTodoAiService(LangchainLinearWorkflowRequest request,
                                                               AtomicInteger toolCalls,
                                                               Map<String, String> datasetRefs) {
        AiServices<LangchainTodoExecutionAiService> builder = AiServices
                .builder(LangchainTodoExecutionAiService.class)
                .chatModel(request.executionModelOrDefault())
                .systemMessageProvider(ignored -> promptService.dagReactSystemPrompt())
                .maxToolCallingRoundTrips(resolveMaxToolRoundTrips(request.getMaxToolRoundTrips()))
                .chatRequestTransformer(chatRequest -> {
                    ensureRunnable(request);
                    return chatRequest;
                })
                .beforeToolExecution(ignored -> ensureRunnable(request))
                .toolExecutionErrorHandler(LangchainTerminalToolErrorHandler::handle)
                .afterToolExecution(result -> {
                    toolCalls.incrementAndGet();
                    if (result != null && result.result() != null) {
                        DatasetRefRegistry.registerFromJson(result.result(), datasetRefs);
                    }
                });
        toolProvider.ifAvailable(builder::toolProvider);
        return builder.build();
    }

    private LangchainFinalAnswerAiService buildFinalAnswerAiService(LangchainLinearWorkflowRequest request) {
        return AiServices.builder(LangchainFinalAnswerAiService.class)
                .chatModel(request.finalAnswerModelOrDefault())
                .systemMessageProvider(ignored -> promptService.dagReactSystemPrompt())
                .chatRequestTransformer(chatRequest -> {
                    ensureRunnable(request);
                    return chatRequest;
                })
                .build();
    }

    private void ensureRunnable(LangchainLinearWorkflowRequest request) {
        if (request == null) {
            return;
        }
        Optional<String> stop = executionGuard.stopReason(request.getRunId(), request.getUserId());
        if (stop.isPresent()) {
            throw new IllegalStateException("RUN_INTERRUPTED:" + stop.get());
        }
    }

    private int resolveMaxToolRoundTrips(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_MAX_TOOL_ROUND_TRIPS;
        }
        return Math.max(1, Math.min(requested, 30));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

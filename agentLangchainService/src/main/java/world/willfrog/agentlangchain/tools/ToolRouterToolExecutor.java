package world.willfrog.agentlangchain.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import world.willfrog.agent.workflow.DatasetRefRegistry;
import world.willfrog.agent.tools.router.ToolRouter;

import java.util.Map;

/**
 * LC4j {@link ToolExecutor} that delegates all tool calls to legacy {@link ToolRouter}.
 */
@RequiredArgsConstructor
final class ToolRouterToolExecutor implements ToolExecutor {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ToolRouter toolRouter;
    private final ObjectMapper objectMapper;

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        Map<String, Object> params = parseArguments(request.arguments());
        LangchainRepeatedToolCallGuard.Decision repeatDecision =
                LangchainRepeatedToolCallGuard.beforeInvoke(request.name(), params, objectMapper);
        if (repeatDecision.blocked()) {
            return repeatDecision.outputOrHint();
        }
        String output = toolRouter.invokeWithMeta(request.name(), params).getOutput();
        Map<String, String> datasetRefs = LangchainDatasetRefContext.snapshot();
        DatasetRefRegistry.registerFromJson(output, datasetRefs);
        LangchainDatasetRefContext.set(datasetRefs);
        output = appendDatasetRetryHintIfNeeded(output, datasetRefs);
        return appendRepeatedToolCallHintIfNeeded(output, repeatDecision);
    }

    @Override
    public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
        if (context != null) {
            LangchainRunContextBridge.apply(context.invocationParameters());
        }
        String output = execute(request, context == null ? null : context.chatMemoryId());
        return ToolExecutionResult.builder()
                .resultText(output)
                .build();
    }

    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(arguments, MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of("raw", arguments);
        }
    }

    private String appendDatasetRetryHintIfNeeded(String output, Map<String, String> datasetRefs) {
        if (output == null || output.isBlank()) {
            return output;
        }
        String lower = output.toLowerCase();
        boolean datasetError = lower.contains("missing_dataset_ids")
                || lower.contains("missing dataset_ids")
                || lower.contains("invalid dataset_ids")
                || lower.contains("dataset_id directory not found")
                || (lower.contains("dataset_ids") && containsFailureWord(lower));
        if (!datasetError) {
            return output;
        }
        StringBuilder hint = new StringBuilder(output);
        hint.append("\n\n_retry_hint_: executePython failed because dataset_ids was missing or invalid. ");
        if (datasetRefs != null && !datasetRefs.isEmpty()) {
            hint.append("Use only these existing dataset_ids exactly: ");
            hint.append(String.join(",", datasetRefs.keySet()));
            hint.append(". Do not use placeholders such as placeholder/data/test and do not hand-code market data.");
        } else {
            hint.append("Call a market data tool first and use the returned data.dataset_id or data.dataset_ids exactly.");
        }
        return hint.toString();
    }

    private boolean containsFailureWord(String lowerOutput) {
        return lowerOutput.contains("error")
                || lowerOutput.contains("failed")
                || lowerOutput.contains("failure")
                || lowerOutput.contains("missing")
                || lowerOutput.contains("invalid")
                || lowerOutput.contains("not found");
    }

    private String appendRepeatedToolCallHintIfNeeded(String output,
                                                      LangchainRepeatedToolCallGuard.Decision repeatDecision) {
        if (repeatDecision == null || !repeatDecision.repeated() || repeatDecision.blocked()) {
            return output;
        }
        String base = output == null ? "" : output;
        return base + "\n\n_retry_hint_: " + repeatDecision.outputOrHint();
    }
}

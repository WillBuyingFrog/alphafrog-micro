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
 * LC4j {@link ToolExecutor}：模型在 tool loop 里发起一次 tool call 后，由此类把请求转给
 * legacy {@link ToolRouter}，并处理 langchain 路径特有的防护与提示。
 *
 * <p>与 {@link ToolRouterToolProvider} 的配合：Provider 负责「有哪些工具」；本类负责
 * 「选中某个工具后怎么跑」。所有工具名最终都进入 {@link ToolRouter#invokeWithMeta(String, Map)}，
 * 因此预算检查、observability trace、结果缓存、统一 JSON 响应格式都在 ToolRouter 内完成，
 * 本类不重复实现那些横切逻辑。</p>
 *
 * <p>单次调用的处理顺序（{@link #execute}）：</p>
 * <ol>
 *   <li>把 LC4j 传来的 arguments JSON 解析为 {@code Map<String, Object>}；</li>
 *   <li>{@link LangchainRepeatedToolCallGuard}：同一 run 内相同工具+相同参数重复超过阈值则直接返回错误文本，
 *       避免模型死循环刷工具；</li>
 *   <li>{@link ToolRouter#invokeWithMeta} 执行并取 output 字符串；</li>
 *   <li>从 output 解析 {@code dataset_id}，写入 {@link DatasetRefRegistry} 与
 *       {@link LangchainDatasetRefContext}，供 DAG 下游 todo 或 executePython 引用；</li>
 *   <li>若 output 暗示 dataset 缺失/无效，或发生重复调用，在结果末尾追加 {@code _retry_hint_}
 *       引导模型改参（不抛异常，让模型在下一轮 tool loop 自行纠正）。</li>
 * </ol>
 *
 * <p>面试常考点：</p>
 * <ul>
 *   <li>「LC4j tool call 怎么落到 MarketDataTools？」→ 本类 → ToolRouter → 具体工具 Bean；</li>
 *   <li>「为什么 cancel 后还能拦住后续工具/LLM？」→ LC4j 层由
 *       {@link world.willfrog.agentlangchain.orchestration.LangchainRunExecutionGuard} 在发 LLM 前和工具前检查；
 *       {@link ToolRouter} 负责预算与工具运行时横切逻辑，不承担 cancel 状态机；</li>
 *   <li>「dataset 怎么跨 todo 传递？」→ 本类注册 ref + TodoNodeExecutor 把 refs 写进 user message。</li>
 * </ul>
 *
 * @see ToolRouterToolProvider 工具目录入口
 * @see world.willfrog.agent.tools.router.ToolRouter 统一执行与观测
 * @see LangchainRepeatedToolCallGuard 重复调用防护
 * @see world.willfrog.agentlangchain.orchestration.LangchainTodoNodeExecutor tool loop 宿主
 */
@RequiredArgsConstructor
final class ToolRouterToolExecutor implements ToolExecutor {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ToolRouter toolRouter;
    private final ObjectMapper objectMapper;

    /**
     * LC4j 旧版回调：返回工具输出纯文本。{@link #executeWithContext} 是推荐路径，会先同步
     * {@link InvocationContext} 里的 run 上下文。
     */
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

    /**
     * LC4j 带上下文执行：先把 {@link InvocationContext#invocationParameters()} 灌进
     * {@link world.willfrog.agent.platform.context.AgentContext}（经 {@link LangchainRunContextBridge}），再调用 {@link #execute}。
     */
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

    /** 解析模型输出的 tool arguments JSON；解析失败时保留 raw 字段避免整次调用 NPE。 */
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

    /**
     * executePython 等工具若因 dataset_ids 错误失败，把当前 run 已知的 ref 列表写进 hint，
     * 减少模型编造 placeholder dataset_id。
     */
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

    /** 未 blocked 但已重复调用时，在 output 末尾追加提示，供模型下一轮改参。 */
    private String appendRepeatedToolCallHintIfNeeded(String output,
                                                      LangchainRepeatedToolCallGuard.Decision repeatDecision) {
        if (repeatDecision == null || !repeatDecision.repeated() || repeatDecision.blocked()) {
            return output;
        }
        String base = output == null ? "" : output;
        return base + "\n\n_retry_hint_: " + repeatDecision.outputOrHint();
    }
}

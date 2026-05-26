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

/**
 * DAG（有向无环图）中单个 todo 节点的执行器，也是 LC4j（LangChain4j）工具调用循环的核心封装。
 * <p>
 * 职责边界：
 * <ul>
 *   <li>把一个 {@link TodoItem} 翻译成 LLM 可理解的 user message；</li>
 *   <li>通过 LC4j {@link AiServices} 构建“模型 ↔ 工具”的交互循环（tool loop / tool calling round trips）；</li>
 *   <li>在循环中实时检查取消/暂停信号（{@link LangchainRunExecutionGuard}），保证 run 被中断后不会继续发请求；</li>
 *   <li>收集工具执行结果中的 dataset ref（跨节点数据传递的引用），供下游 todo 使用；</li>
 *   <li>所有 todo 执行完毕后，调用 {@link #writeFinalAnswer} 生成面向用户的最终回答。</li>
 * </ul>
 * <p>
 * 与上下游协作关系：
 * <ul>
 *   <li>上游调度：由 {@link LangchainDagWorkflowExecutor} 决定什么时候调用本类（依赖图就绪后并发调度）；</li>
 *   <li>Prompt 来源：{@link AgentPromptService#dagReactSystemPrompt()} 提供系统提示词（system prompt），包含时间基准、角色设定、工具使用规范；</li>
 *   <li>消息拼装：{@link LangchainTodoUserMessageBuilder} 负责把 todo 描述、已完成 todo 列表、dataset refs 拼成 user message；</li>
 *   <li>预算/观测：{@link AtomicInteger} toolCalls 由上游传入，用于累加 run 级别的总工具调用次数；{@link AgentContext} 用于注入 tracing/observability 上下文。</li>
 * </ul>
 * <p>
 * 面试常考点：
 * <ul>
 *   <li>“一个 todo 是怎么执行的？” → 看 {@link #execute} 和 {@link #buildTodoAiService}；</li>
 *   <li>“怎么防止 run 被取消后继续发 LLM 请求？” → 看 {@link #ensureRunnable} 和 {@code chatRequestTransformer / beforeToolExecution}；</li>
 *   <li>“tool loop 最多跑多少轮？” → {@link #DEFAULT_MAX_TOOL_ROUND_TRIPS}（30 轮），与 run-level {@code maxToolCalls} 是两层限制；</li>
 *   <li>“dataset 怎么跨 todo 传递？” → 通过 {@link LangchainDatasetRefContext} + {@link DatasetRefRegistry} 注册/读取 JSON 片段。</li>
 * </ul>
 *
 * @see LangchainDagWorkflowExecutor DAG 调度层
 * @see AgentPromptService Prompt 装配层
 * @see LangchainLinearRunPipelineImpl Run 总控层
 */
@Component
@RequiredArgsConstructor
public class LangchainTodoNodeExecutor {

    /**
     * 单个 todo 内部 LC4j 工具调用循环（tool loop）的默认上限。
     * <p>
     * 含义：模型每次输出决定调用工具 → 执行工具 → 把结果喂回模型，算 1 个 round trip。
     * 当模型连续多轮都选择调用工具时，最多允许 30 轮，超过后 LC4j 会停止循环、返回当前结果。
     * <p>
     * 注意：这是<strong>单 todo 级</strong>限制，与 run-level 的 {@code maxToolCalls}（整个 run 所有 todo 的累计工具调用上限）
     * 是两层独立限制。此前（commit ddf3dce 之前）这个值是 8，导致复杂回测 todo 内工具未执行完就被截断，后改为 30。
     */
    private static final int DEFAULT_MAX_TOOL_ROUND_TRIPS = 30;

    /** Prompt 装配服务，负责提供 system prompt 及各类 stage prompt。 */
    private final AgentPromptService promptService;

    /**
     * LC4j 的 ToolProvider，负责把可用工具列表注入到 AiServices。
     * 使用 {@link ObjectProvider} 懒加载，当没有可用工具时可为空（例如纯总结类 todo）。
     */
    private final ObjectProvider<ToolProvider> toolProvider;

    /**
     * 执行守卫，用于检查当前 run 是否被用户取消（cancel）或暂停（pause）。
     * 在 tool loop 的每次往返前后都会检查，防止 run 已中断但仍在发 LLM 请求。
     */
    private final LangchainRunExecutionGuard executionGuard;

    /**
     * 执行单个 DAG todo 节点：构建 user message → 启动 LC4j tool loop → 收集结果。
     * <p>
     * 执行流程：
     * <ol>
     *   <li>参数校验：todo 不能为空；</li>
     *   <li>上下文注入：把 todoId + sequence 写入 {@link AgentContext}，供下游 observability/tracing 使用；</li>
     *   <li>消息拼装：调用 {@link LangchainTodoUserMessageBuilder} 把用户目标、已完成 todo、dataset refs、todo 描述拼成 user message；</li>
     *   <li>数据集上下文：通过 {@link LangchainDatasetRefContext#set} 让工具执行时能读取上游 todo 产出的数据引用；</li>
     *   <li>清除重复调用标记：{@link LangchainRepeatedToolCallContext#clear()} 防止历史状态干扰当前 todo；</li>
     *   <li>可运行性检查：{@link #ensureRunnable} 判断 run 是否已被取消/暂停；</li>
     *   <li>启动 tool loop：{@link #buildTodoAiService} 构建 AiServices 并执行；</li>
     *   <li>结果处理：trim 后写入 {@link DatasetRefRegistry}（供下游 todo 读取），返回成功/失败封装。</li>
     * </ol>
     * <p>
     * 失败场景：
     * <ul>
     *   <li>todo 为空 → 返回 {@code todo_item_required}；</li>
     *   <li>run 被取消/暂停 → {@link #ensureRunnable} 抛异常，catch 后转为失败结果；</li>
     *   <li>模型输出为空字符串 → 返回 {@code empty_todo_output}；</li>
     *   <li>tool loop 内异常（如工具执行失败、LLM 请求超时）→ 由 {@link LangchainTerminalToolErrorHandler} 处理，最终在这里 catch 并封装失败。</li>
     * </ul>
     * <p>
     * 注意：{@code toolCalls} 是上游传入的引用，本方法在 tool loop 的 {@code afterToolExecution} 回调里对其进行 increment，
     * 因此调用前后差值即为当前 todo 实际消耗的工具调用次数。
     *
     * @param request      当前 run 的完整请求上下文（含模型、用户目标、runId 等）
     * @param item         待执行的 todo 节点
     * @param completedTodos 本 todo 之前已完成的 todo 列表（用于上下文拼接）
     * @param datasetRefs  跨 todo 数据集引用表（key=引用名, value=JSON 片段）
     * @param toolCalls    run 级工具调用计数器（引用类型，会被修改）
     * @return 执行结果，成功时包含模型输出文本及本 todo 消耗的工具调用次数
     */
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

    /**
     * 生成面向用户的最终回答（final answer）。
     * <p>
     * 触发时机：DAG 中所有 todo 执行完毕（或最后一个 todo 输出后），由 {@link LangchainDagWorkflowExecutor} 调用。
     * 与 {@link #execute} 的区别：
     * <ul>
     *   <li>不再触发工具调用（no tool loop），纯文本生成；</li>
     *   <li>使用 final answer 专用模型（可配置与 execution 阶段不同的模型/endpoint）；</li>
     *   <li>user message 由 {@link LangchainTodoUserMessageBuilder#buildFinalUserMessage} 拼装，包含用户原始目标 + 全部已完成 todo 的结果汇总。</li>
     * </ul>
     * <p>
     * 面试注意点：如果 final answer 为空，会在 {@link LangchainDagWorkflowExecutor} 层被判定为失败（empty final answer failed）。
     *
     * @param request        当前 run 请求上下文
     * @param completedTodos 所有已完成 todo 的结果列表
     * @return 面向用户的最终回答文本
     */
    public String writeFinalAnswer(LangchainLinearWorkflowRequest request,
                                   List<LangchainCompletedTodo> completedTodos) {
        ensureRunnable(request);
        return buildFinalAnswerAiService(request)
                .answer(LangchainTodoUserMessageBuilder.buildFinalUserMessage(
                        promptService,
                        request.getUserGoal(),
                        completedTodos));
    }

    /**
     * 构建用于执行单个 todo 的 LC4j {@link AiServices} 实例。
     * <p>
     * 配置要点（面试常问）：
     * <ul>
     *   <li><b>chatModel</b>：使用 execution 阶段模型（{@code request.executionModelOrDefault()}），可与 planning/final answer 阶段不同；</li>
     *   <li><b>systemMessageProvider</b>：每次请求前动态获取 system prompt（{@link AgentPromptService#dagReactSystemPrompt()}），
     *       保证时间基准、角色设定等上下文实时生效；</li>
     *   <li><b>maxToolCallingRoundTrips</b>：单 todo 内模型↔工具的最大往返轮数，默认 30，受 {@link #resolveMaxToolRoundTrips} 约束；</li>
     *   <li><b>chatRequestTransformer</b>：每次发 LLM 请求前检查 run 是否被取消/暂停（{@link #ensureRunnable}），
     *       防止用户已点 cancel 但请求仍发出；</li>
     *   <li><b>beforeToolExecution</b>：工具执行前再次检查可运行性，防止在 tool loop 中间被中断后继续执行工具；</li>
     *   <li><b>toolExecutionErrorHandler</b>：工具执行异常处理器（{@link LangchainTerminalToolErrorHandler#handle}），
     *       决定某个工具失败时是整个 todo 失败还是继续尝试；</li>
     *   <li><b>afterToolExecution</b>：工具执行成功后回调，做两件事：
     *       <ol>
     *         <li>累加 run 级 {@code toolCalls} 计数器；</li>
     *         <li>把工具返回的 JSON 结果中的 dataset ref 注册到 {@link DatasetRefRegistry}，供下游 todo 读取。</li>
     *       </ol>
     *   </li>
     *   <li><b>toolProvider</b>：注入可用工具列表；如果当前 run 没有可用工具（如纯分析类 todo），则跳过。</li>
     * </ul>
     *
     * @param request     当前 run 请求上下文
     * @param toolCalls   run 级工具调用计数器（引用，会被 increment）
     * @param datasetRefs 跨 todo 数据集引用表
     * @return 配置完成的 LC4j AiServices 代理实例
     */
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

    /**
     * 构建用于生成最终回答（final answer）的 LC4j {@link AiServices} 实例。
     * <p>
     * 与 {@link #buildTodoAiService} 的核心区别：
     * <ul>
     *   <li>不使用 {@code toolProvider}，即不注入任何工具 → 模型不会触发工具调用，纯文本生成；</li>
     *   <li>使用 final answer 专用模型（{@code request.finalAnswerModelOrDefault()}），可与 execution 阶段不同；</li>
     *   <li>不带 {@code maxToolCallingRoundTrips}、{@code beforeToolExecution}、{@code afterToolExecution} 等 tool loop 相关配置。</li>
     * </ul>
     * <p>
     * 仍保留 {@code chatRequestTransformer} 检查取消/暂停，防止用户在最后一步点前 cancel 但请求仍发出。
     *
     * @param request 当前 run 请求上下文
     * @return 配置完成的 final answer AiServices 代理实例
     */
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

    /**
     * 检查当前 run 是否仍允许继续执行。
     * <p>
     * 机制：通过 {@link LangchainRunExecutionGuard#stopReason} 查询 run 是否被用户取消（cancel）或暂停（pause）。
     * 若存在停止原因，则抛出 {@link IllegalStateException}，异常消息格式为 {@code RUN_INTERRUPTED:<reason>}，
     * 上游 catch 后会将当前 todo 标记为失败。
     * <p>
     * 调用点（三道防线）：
     * <ol>
     *   <li>{@link #execute} 开始时；</li>
     *   <li>{@link #buildTodoAiService} 的 {@code chatRequestTransformer} —— 每次发 LLM 请求前；</li>
     *   <li>{@link #buildTodoAiService} 的 {@code beforeToolExecution} —— 每次执行工具前。</li>
     * </ol>
     * <p>
     * 面试注意：如果缺少第 2、3 道防线，用户点击 cancel 后，已发出的 LLM 请求可能在 tool loop 中继续执行多轮，
     * 造成资源浪费和 observability 混乱。
     *
     * @param request 当前 run 请求上下文，含 runId 和 userId
     */
    private void ensureRunnable(LangchainLinearWorkflowRequest request) {
        if (request == null) {
            return;
        }
        Optional<String> stop = executionGuard.stopReason(request.getRunId(), request.getUserId());
        if (stop.isPresent()) {
            throw new IllegalStateException("RUN_INTERRUPTED:" + stop.get());
        }
    }

    /**
     * 解析单个 todo 内工具调用循环（tool loop）的最大往返轮数。
     * <p>
     * 优先级：
     * <ol>
     *   <li>如果上游传了有效值（{@code requested != null && requested > 0}），则使用上游值，但会被钳制在 [1, 30] 范围内；</li>
     *   <li>否则使用默认值 {@link #DEFAULT_MAX_TOOL_ROUND_TRIPS}（30）。</li>
     * </ol>
     * <p>
     * 上限 30 的考量：防止模型陷入无限工具调用循环（例如反复调用同一工具但参数无实质变化），
     * 同时给复杂 todo（如需要查多支股票、多次回测）留足空间。
     * <p>
     * 注意：这是 LC4j 层限制，与 run-level 的 {@code maxToolCalls}（Nacos 配置 {@code runtime.runBudget.maxToolCalls}）不同。
     * 实际运行中，先触达哪个上限就按哪个停止。
     *
     * @param requested 上游请求中指定的 maxToolRoundTrips，可能为 null
     * @return 实际生效的最大往返轮数
     */
    private int resolveMaxToolRoundTrips(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_MAX_TOOL_ROUND_TRIPS;
        }
        return Math.max(1, Math.min(requested, 30));
    }

    /**
     * 判断字符串是否为空白（null、空串或仅含空白字符）。
     * <p>
     * 用于检测模型输出是否为空，若为空则把当前 todo 标记为失败（{@code empty_todo_output}），
     * 防止下游 todo 或 final answer 拿到无意义内容继续执行。
     *
     * @param value 待检测字符串
     * @return true 当且仅当字符串为 null 或 trim 后为空
     */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

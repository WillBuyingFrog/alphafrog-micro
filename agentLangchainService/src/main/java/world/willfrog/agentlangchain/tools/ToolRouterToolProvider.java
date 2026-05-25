package world.willfrog.agentlangchain.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.RequiredArgsConstructor;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agent.tools.search.SearchTools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LangChain4j（LC4j）{@link ToolProvider} 适配层：把内部 {@link ToolRouter} 工具目录暴露给
 * {@link dev.langchain4j.service.AiServices}，使模型在 tool loop 里看到的工具列表与 legacy 路径一致。
 *
 * <p>职责边界（与 {@link ToolRouterToolExecutor}、{@link ToolRouter} 分工）：</p>
 * <ul>
 *   <li><b>本类</b>：决定「这次 AiService 调用里 LLM 能看见哪些工具」——生成
 *       {@link ToolSpecification} 列表，并为每个 spec 绑定同一个 {@link ToolRouterToolExecutor}；</li>
 *   <li>{@link ToolRouterToolExecutor}：模型选中某个 tool 后，把 JSON 参数解析并委托
 *       {@link ToolRouter#invokeWithMeta(String, Map)} 执行；</li>
 *   <li>{@link ToolRouter}：预算、trace、缓存、参数别名、业务工具分发（见 codex 负责的
 *       {@code ToolRouter.java} 注释）。</li>
 * </ul>
 *
 * <p>调用链（面试常问「工具怎么进 raw HTTP tools 数组」）：</p>
 * <ol>
 *   <li>{@link world.willfrog.agentlangchain.orchestration.LangchainTodoNodeExecutor} 构建 AiServices 时
 *       注入 {@code ObjectProvider<ToolProvider>}（Spring Bean 为 {@link world.willfrog.agentlangchain.config.LangchainToolsConfiguration#langchainToolProvider}）；</li>
 *   <li>每次 LC4j 发起 chat 前，若 {@link #isDynamic()} 为 true，会调用 {@link #provideTools(ToolProviderRequest)}；</li>
 *   <li>{@link LangchainRunContextBridge} 把 {@link InvocationParameters} 里的 runId/userId 写回
 *       {@link AgentContext}，供后续 {@link ToolRouter} 记 observability；</li>
 *   <li>{@link ToolCatalogBuilder} 从 {@link MarketDataTools} 等 Bean 反射生成 spec，并按
 *       webSearch / codeInterpreter 开关过滤，再 {@code mergeCanonical} 合入
 *       {@code checkParallelLimits}（避免 0 参数 {@code @Tool} 反射漏注册）；</li>
 *   <li>返回的 {@link ToolProviderResult} 中，所有 spec 共享一个 {@link ToolRouterToolExecutor} 实例。</li>
 * </ol>
 *
 * <p>面试常考点：</p>
 * <ul>
 *   <li>「langchain 和 legacy 工具列表为何一致？」→ 同一套 {@link ToolCatalogBuilder} 过滤逻辑；</li>
 *   <li>「为什么 searchWeb / executePython 有时不在 tools 里？」→
 *       {@link LangchainToolInvocationKeys#WEB_SEARCH_ENABLED}、
 *       {@link LangchainToolInvocationKeys#CODE_INTERPRETER_ENABLED}；</li>
 *   <li>「Provider 和 Executor 为什么要拆两个类？」→ LC4j 接口分离：目录发现 vs 单次 invoke。</li>
 * </ul>
 *
 * @see ToolRouterToolExecutor 工具执行委托
 * @see ToolCatalogBuilder run 级工具目录拼装
 * @see world.willfrog.agentlangchain.orchestration.LangchainTodoNodeExecutor AiServices 注入点
 * @see world.willfrog.agent.tools.router.ToolRouter 统一执行语义
 */
@RequiredArgsConstructor
public class ToolRouterToolProvider implements ToolProvider {

    private final ToolRouter toolRouter;
    private final MarketDataTools marketDataTools;
    private final RagTools ragTools;
    private final SearchTools searchTools;
    private final PythonSandboxTools pythonSandboxTools;
    private final ObjectMapper objectMapper;

    /**
     * 为当前 LC4j 调用构建「工具名 → ToolExecutor」映射。
     *
     * <p>注意：所有 {@link ToolSpecification} 共用同一个 {@link ToolRouterToolExecutor}，
     * 真正按工具名分支的逻辑在 {@link ToolRouter} 内部，不在 Provider 层。</p>
     */
    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        // 把 Pipeline / TodoNodeExecutor 塞进 InvocationParameters 的 run 上下文同步到 AgentContext
        LangchainRunContextBridge.apply(request);

        boolean webSearchEnabled = resolveBoolean(
                request.invocationParameters(),
                LangchainToolInvocationKeys.WEB_SEARCH_ENABLED,
                AgentContext.isWebSearchEnabled(),
                false
        );
        boolean codeInterpreterEnabled = resolveBoolean(
                request.invocationParameters(),
                LangchainToolInvocationKeys.CODE_INTERPRETER_ENABLED,
                true,
                true
        );

        List<ToolSpecification> specifications = ToolCatalogBuilder.buildSpecifications(
                marketDataTools,
                ragTools,
                searchTools,
                pythonSandboxTools,
                webSearchEnabled,
                codeInterpreterEnabled
        );

        ToolExecutor executor = new ToolRouterToolExecutor(toolRouter, objectMapper);
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        for (ToolSpecification specification : specifications) {
            tools.put(specification, executor);
        }
        return ToolProviderResult.builder()
                .addAll(tools)
                .build();
    }

    /**
     * 动态工具目录：每次 AiService 调用都会重新 {@link #provideTools}，
     * 以便按 run 能力开关（webSearch 等）调整可见工具，而不是进程启动时固定一份列表。
     */
    @Override
    public boolean isDynamic() {
        return true;
    }

    /**
     * 解析布尔开关：优先 InvocationParameters，其次 AgentContext（仅当 runId 已设置），最后默认值。
     */
    private static boolean resolveBoolean(InvocationParameters parameters,
                                            String key,
                                            boolean contextFallback,
                                            boolean defaultValue) {
        if (parameters != null && parameters.containsKey(key)) {
            Boolean value = parameters.get(key);
            return value != null ? value : defaultValue;
        }
        if (AgentContext.getRunId() != null) {
            return contextFallback;
        }
        return defaultValue;
    }
}

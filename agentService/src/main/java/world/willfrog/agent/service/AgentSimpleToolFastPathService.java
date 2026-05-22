package world.willfrog.agent.service;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.tool.ToolRouter;
import world.willfrog.agent.workflow.WorkflowExecutionResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 简单单工具查询的保守 fast-path。
 */
@Service
public class AgentSimpleToolFastPathService {

    private static final Pattern TS_CODE_PATTERN = Pattern.compile("\\b\\d{6}\\.(?:SH|SZ|BJ)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIX_DIGIT_PATTERN = Pattern.compile("\\b\\d{6}\\b");
    private static final List<String> MULTI_STEP_SIGNALS = List.of(
            "计算", "收益", "对比", "分析", "然后", "同时", "专家", "观点", "定投", "去年", "今年", "每个月", "组合"
    );

    private final ToolRouter toolRouter;

    @Value("${agent.flow.fast-path.enabled:true}")
    private boolean enabled;

    public AgentSimpleToolFastPathService(ToolRouter toolRouter) {
        this.toolRouter = toolRouter;
    }

    public Optional<WorkflowExecutionResult> tryExecute(String userGoal, List<ToolSpecification> toolSpecifications) {
        Optional<FastPathDecision> decision = decide(userGoal, toolSpecifications);
        if (decision.isEmpty() || !decision.get().selected()) {
            return Optional.empty();
        }
        return Optional.of(execute(decision.get()));
    }

    public WorkflowExecutionResult execute(FastPathDecision selected) {
        ToolRouter.ToolInvocationResult invocation = toolRouter.invokeWithMeta(selected.toolName(), selected.params());
        String answer = buildMarkdownAnswer(selected, invocation);
        return WorkflowExecutionResult.builder()
                .success(invocation.isSuccess())
                .failureReason(invocation.isSuccess() ? "" : "fast_path_tool_failed:" + selected.toolName())
                .finalAnswer(answer)
                .toolCallsUsed(1)
                .build();
    }

    public Optional<FastPathDecision> decide(String userGoal, List<ToolSpecification> toolSpecifications) {
        if (!enabled || userGoal == null || userGoal.isBlank()) {
            return Optional.empty();
        }
        String normalized = userGoal.trim();
        if (containsMultiStepSignal(normalized)) {
            return Optional.of(FastPathDecision.skipped("multi_step_signal"));
        }
        Set<String> availableTools = toolSpecifications == null ? Set.of() : toolSpecifications.stream()
                .map(ToolSpecification::name)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toSet());

        Optional<FastPathDecision> codeDecision = decideByCode(normalized, availableTools);
        if (codeDecision.isPresent()) {
            return codeDecision;
        }
        Optional<FastPathDecision> keywordDecision = decideByKeyword(normalized, availableTools);
        return keywordDecision.isPresent() ? keywordDecision : Optional.of(FastPathDecision.skipped("no_single_tool_match"));
    }

    private Optional<FastPathDecision> decideByCode(String text, Set<String> availableTools) {
        Matcher tsCode = TS_CODE_PATTERN.matcher(text);
        if (tsCode.find()) {
            String code = tsCode.group().toUpperCase(Locale.ROOT);
            if (isIndexQuery(text) && availableTools.contains("getIndexInfo")) {
                return Optional.of(FastPathDecision.selected("getIndexInfo", Map.of("tsCode", code)));
            }
            if (availableTools.contains("getStockInfo")) {
                return Optional.of(FastPathDecision.selected("getStockInfo", Map.of("tsCode", code)));
            }
        }
        Matcher sixDigit = SIX_DIGIT_PATTERN.matcher(text);
        if (sixDigit.find()) {
            String keyword = sixDigit.group();
            if (isIndexQuery(text) && availableTools.contains("searchIndex")) {
                return Optional.of(FastPathDecision.selected("searchIndex", Map.of("keyword", keyword)));
            }
            if (availableTools.contains("searchStock")) {
                return Optional.of(FastPathDecision.selected("searchStock", Map.of("keyword", keyword)));
            }
        }
        return Optional.empty();
    }

    private Optional<FastPathDecision> decideByKeyword(String text, Set<String> availableTools) {
        String keyword = extractKeyword(text);
        if (keyword.isBlank()) {
            return Optional.empty();
        }
        if (isIndexQuery(text) && availableTools.contains("searchIndex")) {
            return Optional.of(FastPathDecision.selected("searchIndex", Map.of("keyword", keyword)));
        }
        if (availableTools.contains("searchAssetInfo") && shouldPreferEtfSearch(text)) {
            return Optional.of(FastPathDecision.selected("searchAssetInfo", Map.of(
                    "query", keyword,
                    "assetTypes", "etf",
                    "marketScope", "domestic"
            )));
        }
        if (text.contains("基金") && availableTools.contains("searchFund") && !shouldPreferEtfSearch(text)) {
            return Optional.of(FastPathDecision.selected("searchFund", Map.of("keyword", keyword)));
        }
        if ((text.contains("股票") || text.contains("股价") || text.contains("个股")) && availableTools.contains("searchStock")) {
            return Optional.of(FastPathDecision.selected("searchStock", Map.of("keyword", keyword)));
        }
        return Optional.empty();
    }

    private boolean containsMultiStepSignal(String text) {
        return MULTI_STEP_SIGNALS.stream().anyMatch(text::contains);
    }

    private boolean isIndexQuery(String text) {
        return text.contains("指数")
                || text.contains("沪深300")
                || text.contains("中证500")
                || text.contains("中证1000")
                || text.contains("创业板指")
                || text.contains("上证指数");
    }

    private boolean shouldPreferEtfSearch(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        return upper.contains("ETF")
                || text.contains("etf")
                || text.contains("场内")
                || text.contains("上市")
                || text.contains("行业主题")
                || text.contains("主题ETF")
                || text.contains("主题etf");
    }

    private String extractKeyword(String text) {
        String keyword = text;
        for (String prefix : List.of("请", "帮我", "帮忙", "查询", "查一下", "查", "搜索", "搜一下", "搜")) {
            keyword = keyword.replace(prefix, "");
        }
        for (String suffix : List.of("的指数", "指数", "的股票", "股票", "的基金", "基金", "信息", "代码", "是什么", "是多少", "？", "?")) {
            keyword = keyword.replace(suffix, "");
        }
        return keyword.trim();
    }

    private String buildMarkdownAnswer(FastPathDecision decision, ToolRouter.ToolInvocationResult invocation) {
        StringBuilder answer = new StringBuilder();
        answer.append("已执行工具 `").append(decision.toolName()).append("`。\n\n");
        answer.append("参数：`").append(decision.params()).append("`\n\n");
        answer.append(invocation.isSuccess() ? "结果：" : "工具返回失败：").append("\n\n");
        answer.append("```json\n").append(invocation.getOutput()).append("\n```");
        return answer.toString();
    }

    public record FastPathDecision(boolean selected, String reason, String toolName, Map<String, Object> params) {
        public static FastPathDecision selected(String toolName, Map<String, Object> params) {
            return new FastPathDecision(true, "selected", toolName, new LinkedHashMap<>(params));
        }

        public static FastPathDecision skipped(String reason) {
            return new FastPathDecision(false, reason, "", Map.of());
        }
    }
}

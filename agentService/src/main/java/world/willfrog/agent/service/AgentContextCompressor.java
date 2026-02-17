package world.willfrog.agent.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.entity.AgentRunMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Agent 上下文压缩服务。
 * <p>
 * 职责：
 * 1. 对消息历史进行压缩，控制 Token 消耗
 * 2. 实现滑动窗口策略（默认）：保留最近 N 条消息
 * 3. 支持摘要压缩策略（可配置小模型）
 * 4. 支持按最大字符数截断
 * 5. 返回压缩结果信息（用于事件记录）
 *
 * @author kimi
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentContextCompressor {

    private static final int DEFAULT_SUMMARY_MAX_CHARS = 1200;
    private static final double DEFAULT_SUMMARY_TEMPERATURE = 0.2D;
    private static final int DEFAULT_MIN_MESSAGES_FOR_SUMMARY = 6;
    private static final int DEFAULT_SUMMARY_MAX_MESSAGES = 20;

    private final AgentAiServiceFactory aiServiceFactory;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final AgentLlmProperties llmProperties;

    /**
     * 最大历史消息数（轮数 * 2，因为每轮包含 user + assistant 两条消息）。
     * 默认 5 轮对话 = 10 条消息。
     */
    @Value("${agent.multi-turn.max-history-messages:10}")
    private int maxHistoryMessages;

    /**
     * 单条消息最大字符数（超长消息会被截断）。
     */
    @Value("${agent.multi-turn.max-message-chars:8000}")
    private int maxMessageChars;

    /**
     * 压缩结果记录。
     *
     * @param originalMessages   原始消息数
     * @param compressedMessages 压缩后消息数
     * @param strategy           使用的策略
     * @param droppedSequences   被丢弃的消息序号列表
     */
    public record CompressionResult(
            int originalMessages,
            int compressedMessages,
            String strategy,
            List<Integer> droppedSequences
    ) {
    }

    /**
     * 上下文构建结果。
     *
     * @param text        构建后的上下文文本
     * @param compression 压缩结果信息
     */
    public record ContextBuildResult(
            String text,
            CompressionResult compression
    ) {
    }

    /**
     * 压缩消息历史（滑动窗口策略）。
     * <p>
     * 策略说明：
     * 1. 保留最近 {@link #maxHistoryMessages} 条消息
     * 2. 对每条消息进行长度限制（{@link #maxMessageChars}）
     * 3. 返回压缩结果信息
     *
     * @param messages 原始消息列表（按 seq 升序）
     * @return 压缩后的消息列表
     */
    public List<AgentRunMessage> compress(List<AgentRunMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        // 应用滑动窗口：保留最近 N 条
        List<AgentRunMessage> windowed = applySlidingWindow(messages);

        // 应用长度限制
        List<AgentRunMessage> truncated = applyLengthLimit(windowed);

        return truncated;
    }

    /**
     * 构建对话上下文（支持摘要压缩）。
     *
     * @param messages        原始消息列表
     * @param currentUserGoal 当前轮次用户需求（用于去重）
     * @return 上下文构建结果
     */
    public ContextBuildResult buildCompressedContext(List<AgentRunMessage> messages, String currentUserGoal) {
        if (messages == null || messages.isEmpty()) {
            return new ContextBuildResult("", new CompressionResult(0, 0, "none", List.of()));
        }
        List<AgentRunMessage> history = filterHistoryMessages(messages, currentUserGoal);
        if (history.isEmpty()) {
            return new ContextBuildResult("", new CompressionResult(0, 0, "none", List.of()));
        }

        CompressionConfig config = resolveCompressionConfig();
        if (shouldUseSummary(config, history.size())) {
            String summary = summarizeHistory(history, config);
            if (!summary.isBlank()) {
                String trimmed = trimToMaxChars(summary, config.summaryMaxChars());
                return new ContextBuildResult(
                        trimmed,
                        new CompressionResult(history.size(), 1, "llm_summary", List.of())
                );
            }
        }

        List<AgentRunMessage> compressed = compress(history);
        CompressionResult result = buildCompressionResult(history, compressed, "sliding_window");
        return new ContextBuildResult(formatAsDialogue(compressed), result);
    }

    /**
     * 压缩消息历史并返回详细结果。
     *
     * @param messages 原始消息列表
     * @return 压缩结果（包含压缩后的消息和元信息）
     */
    public CompressionResult compressWithResult(List<AgentRunMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new CompressionResult(0, 0, "sliding_window", List.of());
        }
        List<AgentRunMessage> compressed = compress(messages);
        return buildCompressionResult(messages, compressed, "sliding_window");
    }

    /**
     * 应用滑动窗口策略。
     *
     * @param messages 原始消息列表
     * @return 滑动窗口后的消息列表
     */
    private List<AgentRunMessage> applySlidingWindow(List<AgentRunMessage> messages) {
        if (messages.size() <= maxHistoryMessages) {
            return new ArrayList<>(messages);
        }

        // 保留最近 N 条
        int startIndex = messages.size() - maxHistoryMessages;
        return new ArrayList<>(messages.subList(startIndex, messages.size()));
    }

    /**
     * 应用长度限制（对超长消息进行截断）。
     *
     * @param messages 消息列表
     * @return 处理后的消息列表
     */
    private List<AgentRunMessage> applyLengthLimit(List<AgentRunMessage> messages) {
        List<AgentRunMessage> result = new ArrayList<>();
        for (AgentRunMessage msg : messages) {
            if (msg.getContent() != null && msg.getContent().length() > maxMessageChars) {
                // 创建副本并截断内容
                AgentRunMessage truncated = new AgentRunMessage();
                truncated.setId(msg.getId());
                truncated.setRunId(msg.getRunId());
                truncated.setSeq(msg.getSeq());
                truncated.setRole(msg.getRole());
                truncated.setContent(msg.getContent().substring(0, maxMessageChars) + "\n... [内容已截断]");
                truncated.setMetaJson(msg.getMetaJson());
                truncated.setMsgType(msg.getMsgType());
                truncated.setCreatedAt(msg.getCreatedAt());
                result.add(truncated);
            } else {
                result.add(msg);
            }
        }
        return result;
    }

    /**
     * 估算消息的 Token 数（简化实现）。
     * <p>
     * 规则：
     * - 中文：1 字符 ≈ 1 token
     * - 英文：1 单词 ≈ 1.3 tokens
     * - 简化计算：字符数 / 4
     *
     * @param content 消息内容
     * @return 估算的 Token 数
     */
    public int estimateTokens(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        // 简化估算：每 4 个字符约 1 个 token
        return Math.max(1, content.length() / 4);
    }

    /**
     * 估算消息列表的总 Token 数。
     *
     * @param messages 消息列表
     * @return 总 Token 数
     */
    public int estimateTotalTokens(List<AgentRunMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (AgentRunMessage msg : messages) {
            total += estimateTokens(msg.getContent());
        }
        return total;
    }

    /**
     * 将消息列表格式化为对话文本（用于 Prompt 注入）。
     *
     * @param messages 消息列表
     * @return 格式化的对话文本
     */
    public String formatAsDialogue(List<AgentRunMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是用户与助手的历史对话：\n\n");

        for (AgentRunMessage msg : messages) {
            String roleLabel = switch (msg.getRole()) {
                case AgentRunMessage.ROLE_USER -> "用户";
                case AgentRunMessage.ROLE_ASSISTANT -> "助手";
                case AgentRunMessage.ROLE_SYSTEM -> "系统";
                default -> msg.getRole();
            };
            sb.append(roleLabel).append("：").append(msg.getContent()).append("\n\n");
        }

        return sb.toString();
    }

    private CompressionResult buildCompressionResult(List<AgentRunMessage> original,
                                                     List<AgentRunMessage> compressed,
                                                     String strategy) {
        int originalCount = original == null ? 0 : original.size();
        int compressedCount = compressed == null ? 0 : compressed.size();
        List<Integer> originalSeqs = original == null ? List.of()
                : original.stream().map(AgentRunMessage::getSeq).toList();
        List<Integer> compressedSeqs = compressed == null ? List.of()
                : compressed.stream().map(AgentRunMessage::getSeq).toList();

        List<Integer> dropped = new ArrayList<>();
        for (Integer seq : originalSeqs) {
            if (!compressedSeqs.contains(seq)) {
                dropped.add(seq);
            }
        }
        return new CompressionResult(originalCount, compressedCount, strategy, dropped);
    }

    private List<AgentRunMessage> filterHistoryMessages(List<AgentRunMessage> messages, String currentUserGoal) {
        List<AgentRunMessage> filtered = new ArrayList<>();
        for (AgentRunMessage msg : messages == null ? List.<AgentRunMessage>of() : messages) {
            if (msg == null) {
                continue;
            }
            if (AgentRunMessage.MSG_TYPE_SUMMARY.equals(msg.getMsgType())) {
                continue;
            }
            filtered.add(msg);
        }
        return dropCurrentUserGoal(filtered, currentUserGoal);
    }

    private List<AgentRunMessage> dropCurrentUserGoal(List<AgentRunMessage> messages, String currentUserGoal) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        String goal = currentUserGoal == null ? "" : currentUserGoal.trim();
        if (goal.isBlank()) {
            return new ArrayList<>(messages);
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentRunMessage msg = messages.get(i);
            if (msg == null || msg.getRole() == null) {
                continue;
            }
            if (AgentRunMessage.ROLE_USER.equals(msg.getRole())) {
                String content = msg.getContent() == null ? "" : msg.getContent().trim();
                if (content.equals(goal)) {
                    return new ArrayList<>(messages.subList(0, i));
                }
                break;
            }
        }
        return new ArrayList<>(messages);
    }

    private boolean shouldUseSummary(CompressionConfig config, int messageCount) {
        if (config == null || !config.enabled()) {
            return false;
        }
        if (!"llm_summary".equalsIgnoreCase(config.strategy())) {
            return false;
        }
        return messageCount >= config.minMessagesForSummary();
    }

    private String summarizeHistory(List<AgentRunMessage> messages, CompressionConfig config) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        if (!hasSummaryConfig(config)) {
            log.warn("Summary compression enabled but summary model not configured");
            return "";
        }
        try {
            List<AgentRunMessage> limited = applyLengthLimit(messages);
            limited = applySummaryMessageLimit(limited, config.summaryMaxMessages());
            if (limited.isEmpty()) {
                return "";
            }
            String dialogue = formatAsDialogue(limited);
            if (dialogue.isBlank()) {
                return "";
            }
            ChatLanguageModel model = buildSummaryModel(config);
            if (model == null) {
                return "";
            }
            List<ChatMessage> chatMessages = List.of(
                    new SystemMessage(buildSummarySystemPrompt(config.summaryMaxChars())),
                    new UserMessage(dialogue)
            );
            Response<AiMessage> response = model.generate(chatMessages);
            String summary = response == null || response.content() == null ? "" : nvl(response.content().text());
            return trimToMaxChars(summary, config.summaryMaxChars());
        } catch (Exception e) {
            log.warn("Failed to summarize history, fallback to sliding window: {}", e.getMessage());
            return "";
        }
    }

    private ChatLanguageModel buildSummaryModel(CompressionConfig config) {
        try {
            AgentLlmResolver.ResolvedLlm resolved = aiServiceFactory.resolveLlm(config.summaryEndpoint(), config.summaryModel());
            Double temperature = config.summaryTemperature() <= 0 ? DEFAULT_SUMMARY_TEMPERATURE : config.summaryTemperature();
            if (config.summaryProviderOrder() != null && !config.summaryProviderOrder().isEmpty()) {
                return aiServiceFactory.buildChatModelWithProviderOrderAndTemperature(resolved, config.summaryProviderOrder(), temperature);
            }
            return aiServiceFactory.buildChatModelWithTemperature(resolved, temperature);
        } catch (Exception e) {
            log.warn("Failed to build summary model: {}", e.getMessage());
            return null;
        }
    }

    private String buildSummarySystemPrompt(int maxChars) {
        int limit = maxChars > 0 ? maxChars : DEFAULT_SUMMARY_MAX_CHARS;
        return "你是对话压缩助手，请将历史对话压缩成简洁摘要，保留关键事实、约束、结论、未解决问题与用户偏好。"
                + "不要臆测或补充未出现的信息。输出中文，尽量控制在" + limit + "字以内。";
    }

    private List<AgentRunMessage> applySummaryMessageLimit(List<AgentRunMessage> messages, int maxMessages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int limit = maxMessages > 0 ? maxMessages : DEFAULT_SUMMARY_MAX_MESSAGES;
        if (messages.size() <= limit) {
            return new ArrayList<>(messages);
        }
        return new ArrayList<>(messages.subList(messages.size() - limit, messages.size()));
    }

    private String trimToMaxChars(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        int limit = maxChars > 0 ? maxChars : DEFAULT_SUMMARY_MAX_CHARS;
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit);
    }

    private CompressionConfig resolveCompressionConfig() {
        AgentLlmProperties.Compression local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getMultiTurn)
                .map(AgentLlmProperties.MultiTurn::getCompression)
                .orElse(null);
        AgentLlmProperties.Compression base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getMultiTurn)
                .map(AgentLlmProperties.MultiTurn::getCompression)
                .orElse(null);

        boolean enabled = firstNonNull(local == null ? null : local.getEnabled(),
                base == null ? null : base.getEnabled(),
                false);
        String strategy = firstNonBlank(local == null ? null : local.getStrategy(),
                base == null ? null : base.getStrategy(),
                "sliding_window");
        String summaryEndpoint = firstNonBlank(local == null ? null : local.getSummaryEndpoint(),
                base == null ? null : base.getSummaryEndpoint(),
                "");
        String summaryModel = firstNonBlank(local == null ? null : local.getSummaryModel(),
                base == null ? null : base.getSummaryModel(),
                "");
        int summaryMaxChars = firstPositive(local == null ? null : local.getSummaryMaxChars(),
                base == null ? null : base.getSummaryMaxChars(),
                DEFAULT_SUMMARY_MAX_CHARS);
        double summaryTemperature = firstPositiveDouble(local == null ? null : local.getSummaryTemperature(),
                base == null ? null : base.getSummaryTemperature(),
                DEFAULT_SUMMARY_TEMPERATURE);
        List<String> summaryProviderOrder = resolveProviderOrder(
                local == null ? null : local.getSummaryProviderOrder(),
                base == null ? null : base.getSummaryProviderOrder()
        );
        int minMessagesForSummary = firstPositive(local == null ? null : local.getMinMessagesForSummary(),
                base == null ? null : base.getMinMessagesForSummary(),
                DEFAULT_MIN_MESSAGES_FOR_SUMMARY);
        int summaryMaxMessages = firstPositive(local == null ? null : local.getSummaryMaxMessages(),
                base == null ? null : base.getSummaryMaxMessages(),
                DEFAULT_SUMMARY_MAX_MESSAGES);

        return new CompressionConfig(
                enabled,
                strategy,
                summaryEndpoint,
                summaryModel,
                summaryProviderOrder,
                summaryMaxChars,
                summaryTemperature,
                minMessagesForSummary,
                summaryMaxMessages
        );
    }

    private boolean hasSummaryConfig(CompressionConfig config) {
        if (config == null) {
            return false;
        }
        return !isBlank(config.summaryEndpoint()) || !isBlank(config.summaryModel());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private boolean firstNonNull(Boolean first, Boolean second, boolean fallback) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        return fallback;
    }

    private int firstPositive(Integer first, Integer second, int fallback) {
        if (first != null && first > 0) {
            return first;
        }
        if (second != null && second > 0) {
            return second;
        }
        return fallback;
    }

    private double firstPositiveDouble(Double first, Double second, double fallback) {
        if (first != null && first > 0) {
            return first;
        }
        if (second != null && second > 0) {
            return second;
        }
        return fallback;
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (!isBlank(first)) {
            return first.trim();
        }
        if (!isBlank(second)) {
            return second.trim();
        }
        return fallback;
    }

    private List<String> resolveProviderOrder(List<String> local, List<String> base) {
        if (local != null && !local.isEmpty()) {
            return local;
        }
        if (base != null && !base.isEmpty()) {
            return base;
        }
        return List.of();
    }

    private record CompressionConfig(
            boolean enabled,
            String strategy,
            String summaryEndpoint,
            String summaryModel,
            List<String> summaryProviderOrder,
            int summaryMaxChars,
            double summaryTemperature,
            int minMessagesForSummary,
            int summaryMaxMessages
    ) {
    }
}

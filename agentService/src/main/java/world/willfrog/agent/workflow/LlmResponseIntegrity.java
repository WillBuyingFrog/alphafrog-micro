package world.willfrog.agent.workflow;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import world.willfrog.agent.platform.context.AgentContext;

/**
 * LLM 输出完整性判定：区分正常、可疑空输出、以及 length 截断失败。
 */
final class LlmResponseIntegrity {

    static final String TRUNCATED_RETRY_HINT = """
            上次模型响应因输出长度限制被截断，且没有有效正文内容。
            请缩短内部推理，直接给出结果；如需外部数据或计算，请发起工具调用。
            """;

    enum OutputIntegrityLevel {
        OK,
        SUSPICIOUS,
        TRUNCATED
    }

    private LlmResponseIntegrity() {
    }

    static OutputIntegrityLevel classify(ChatResponse response) {
        if (response == null) {
            return OutputIntegrityLevel.OK;
        }
        String content = extractContent(response);
        if (!isBlank(content)) {
            return OutputIntegrityLevel.OK;
        }
        FinishReason finishReason = extractFinishReason(response);
        if (finishReason == FinishReason.LENGTH) {
            return OutputIntegrityLevel.TRUNCATED;
        }
        if (isSuspiciousEmpty(response, finishReason)) {
            return OutputIntegrityLevel.SUSPICIOUS;
        }
        return OutputIntegrityLevel.OK;
    }

    static String eventType(OutputIntegrityLevel level) {
        return switch (level) {
            case TRUNCATED -> "OUTPUT_TRUNCATED";
            case SUSPICIOUS -> "SUSPICIOUS_EMPTY_OUTPUT";
            case OK -> null;
        };
    }

    private static boolean isSuspiciousEmpty(ChatResponse response, FinishReason finishReason) {
        if (finishReason != null && finishReason != FinishReason.OTHER) {
            return false;
        }
        TokenUsage usage = response.tokenUsage();
        if (usage != null && usage.outputTokenCount() != null && usage.outputTokenCount() > 0) {
            return true;
        }
        String thinking = AgentContext.getThinkingContent();
        return thinking != null && !thinking.isBlank();
    }

    private static String extractContent(ChatResponse response) {
        AiMessage aiMessage = response.aiMessage();
        if (aiMessage == null || aiMessage.text() == null) {
            return "";
        }
        return aiMessage.text();
    }

    private static FinishReason extractFinishReason(ChatResponse response) {
        ChatResponseMetadata metadata = response.metadata();
        return metadata == null ? null : metadata.finishReason();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

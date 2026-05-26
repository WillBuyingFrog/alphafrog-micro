package world.willfrog.agent.workflow;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmResponseIntegrityTest {

    @Test
    void classify_shouldDetectTruncatedEmptyOutput() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(new AiMessage(""))
                .metadata(ChatResponseMetadata.builder().finishReason(FinishReason.LENGTH).build())
                .build();
        assertEquals(LlmResponseIntegrity.OutputIntegrityLevel.TRUNCATED,
                LlmResponseIntegrity.classify(response));
    }

    @Test
    void classify_shouldTreatBlankStopAsOkWhenNoSuspiciousSignals() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(new AiMessage(""))
                .metadata(ChatResponseMetadata.builder().finishReason(FinishReason.STOP).build())
                .build();
        assertEquals(LlmResponseIntegrity.OutputIntegrityLevel.OK,
                LlmResponseIntegrity.classify(response));
    }

    @Test
    void classify_shouldMarkSuspiciousWhenBlankWithOutputTokens() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(new AiMessage(""))
                .metadata(ChatResponseMetadata.builder()
                        .finishReason(FinishReason.OTHER)
                        .tokenUsage(new TokenUsage(10, 100, 110))
                        .build())
                .build();
        assertEquals(LlmResponseIntegrity.OutputIntegrityLevel.SUSPICIOUS,
                LlmResponseIntegrity.classify(response));
    }
}

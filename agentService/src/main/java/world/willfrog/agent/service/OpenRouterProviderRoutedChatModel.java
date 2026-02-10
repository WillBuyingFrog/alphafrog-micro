package world.willfrog.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ai4j.openai4j.OpenAiClient;
import dev.ai4j.openai4j.OpenAiHttpException;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.InternalOpenAiHelper;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Slf4j
public class OpenRouterProviderRoutedChatModel implements ChatLanguageModel {

    private final OpenAiClient client;
    private final ObjectMapper objectMapper;
    private final String modelName;
    private final Double temperature;
    private final Integer maxTokens;
    private final List<String> providerOrder;

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, List.of());
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        String requestJson = null;
        try {
            ChatCompletionRequest.Builder builder = ChatCompletionRequest.builder()
                    .model(nvl(modelName))
                    .messages(InternalOpenAiHelper.toOpenAiMessages(messages == null ? List.of() : messages))
                    .temperature(temperature)
                    .maxTokens(maxTokens);
            if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
                builder.tools(InternalOpenAiHelper.toTools(toolSpecifications, false));
            }
            ChatCompletionRequest request = builder.build();
            Map<String, Object> requestJsonMap = objectMapper.convertValue(
                    request,
                    new TypeReference<Map<String, Object>>() {
                    }
            );
            requestJsonMap.put("provider", Map.of("order", providerOrder));

            requestJson = objectMapper.writeValueAsString(requestJsonMap);
            if (log.isDebugEnabled()) {
                log.debug("OpenRouter provider routing enabled: providers={}", providerOrder);
            }
            String responseJson = client.chatCompletion(requestJson).execute();
            ChatCompletionResponse completion = objectMapper.readValue(responseJson, ChatCompletionResponse.class);

            AiMessage aiMessage = InternalOpenAiHelper.aiMessageFrom(completion);
            TokenUsage tokenUsage = InternalOpenAiHelper.tokenUsageFrom(completion.usage());
            FinishReason finishReason = extractFinishReason(completion);
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (completion.id() != null) {
                metadata.put("id", completion.id());
            }
            if (completion.model() != null) {
                metadata.put("model", completion.model());
            }
            return Response.from(aiMessage, tokenUsage, finishReason, metadata);
        } catch (OpenAiHttpException e) {
            String detail = "OpenRouter provider routed chat completion failed"
                    + " (http=" + e.code()
                    + ", providers=" + providerOrder
                    + ", model=" + nvl(modelName)
                    + ", error=" + shorten(e.getMessage()) + ")";
            log.warn(detail);
            throw new IllegalStateException(detail, e);
        } catch (Exception e) {
            String detail = "OpenRouter provider routed chat completion failed"
                    + " (providers=" + providerOrder
                    + ", model=" + nvl(modelName)
                    + ", error=" + shorten(e.getMessage())
                    + ", request=" + shorten(requestJson) + ")";
            log.warn(detail, e);
            throw new IllegalStateException(detail, e);
        }
    }

    private FinishReason extractFinishReason(ChatCompletionResponse completion) {
        if (completion == null || completion.choices() == null || completion.choices().isEmpty()) {
            return null;
        }
        String raw = completion.choices().get(0).finishReason();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return InternalOpenAiHelper.finishReasonFrom(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ');
        if (normalized.length() <= 600) {
            return normalized;
        }
        return normalized.substring(0, 600) + "...";
    }
}

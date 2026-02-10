package world.willfrog.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.openai.InternalOpenAiHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentLlmRequestSnapshotBuilder {

    private final ObjectMapper objectMapper;

    @Value("${langchain4j.open-ai.temperature:0.7}")
    private Double temperature;

    @Value("${langchain4j.open-ai.max-tokens:4096}")
    private Integer maxTokens;

    public Map<String, Object> buildChatCompletionsRequest(String endpointName,
                                                            String endpointBaseUrl,
                                                            String modelName,
                                                            List<ChatMessage> messages,
                                                            List<ToolSpecification> toolSpecifications,
                                                            Map<String, Object> requestMeta) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("provider", "openai-compatible");
        snapshot.put("endpoint", Map.of(
                "name", nvl(endpointName),
                "baseUrl", nvl(endpointBaseUrl),
                "path", "/chat/completions"
        ));

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
            snapshot.put("request", objectMapper.convertValue(request, new TypeReference<Map<String, Object>>() {
            }));
        } catch (Exception e) {
            log.warn("Build chat completion request snapshot failed: endpoint={} model={}", endpointName, modelName, e);
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("model", nvl(modelName));
            fallback.put("temperature", temperature);
            fallback.put("max_tokens", maxTokens);
            fallback.put("messages", objectMapper.convertValue(messages == null ? List.of() : messages, new TypeReference<List<Object>>() {
            }));
            List<String> toolNames = new ArrayList<>();
            if (toolSpecifications != null) {
                for (ToolSpecification specification : toolSpecifications) {
                    if (specification != null && specification.name() != null && !specification.name().isBlank()) {
                        toolNames.add(specification.name());
                    }
                }
            }
            fallback.put("tool_names", toolNames);
            snapshot.put("request", fallback);
        }

        if (requestMeta != null && !requestMeta.isEmpty()) {
            snapshot.put("meta", requestMeta);
        }
        return snapshot;
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}

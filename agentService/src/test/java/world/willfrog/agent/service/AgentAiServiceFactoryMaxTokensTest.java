package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.config.AgentLlmProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

class AgentAiServiceFactoryMaxTokensTest {

    @Test
    void buildChatModelWithProviderOrder_shouldUseStageMaxTokensOverrideForOpenRouter() {
        AgentAiServiceFactory factory = newFactory();
        AgentLlmResolver.ResolvedLlm resolved = new AgentLlmResolver.ResolvedLlm(
                "openrouter",
                "https://openrouter.ai/api/v1",
                "moonshotai/kimi-k2.6",
                "",
                null,
                List.of()
        );

        ChatModel model = factory.buildChatModelWithProviderOrder(resolved, List.of(), 20000);

        assertInstanceOf(OpenRouterProviderRoutedChatModel.class, model);
        assertEquals(20000, ReflectionTestUtils.getField(model, "maxTokens"));
    }

    @Test
    void buildChatModelWithProviderOrder_shouldFallbackToGlobalDefaultWhenOverrideMissing() {
        AgentAiServiceFactory factory = newFactory();
        AgentLlmResolver.ResolvedLlm resolved = new AgentLlmResolver.ResolvedLlm(
                "openrouter",
                "https://openrouter.ai/api/v1",
                "moonshotai/kimi-k2.6",
                "",
                null,
                List.of()
        );

        ChatModel model = factory.buildChatModelWithProviderOrder(resolved, List.of());

        assertInstanceOf(OpenRouterProviderRoutedChatModel.class, model);
        assertEquals(4096, ReflectionTestUtils.getField(model, "maxTokens"));
    }

    private static AgentAiServiceFactory newFactory() {
        AgentAiServiceFactory factory = new AgentAiServiceFactory(
                mock(AgentLlmResolver.class),
                mock(AgentLlmProperties.class),
                new ObjectMapper(),
                mock(RawHttpLogger.class),
                mock(AgentObservabilityService.class),
                mock(OpenRouterCostService.class),
                mock(AgentLlmLocalConfigLoader.class)
        );
        ReflectionTestUtils.setField(factory, "openAiApiKey", "test-key");
        ReflectionTestUtils.setField(factory, "maxTokens", 4096);
        ReflectionTestUtils.setField(factory, "temperature", 0.7D);
        ReflectionTestUtils.setField(factory, "openRouterHttpReferer", "https://example.com");
        ReflectionTestUtils.setField(factory, "openRouterTitle", "test");
        return factory;
    }
}

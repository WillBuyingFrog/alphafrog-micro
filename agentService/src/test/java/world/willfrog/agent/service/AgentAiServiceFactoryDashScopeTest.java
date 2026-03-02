package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

class AgentAiServiceFactoryDashScopeTest {

    @ParameterizedTest
    @CsvSource({
            "us,https://dashscope-us.aliyuncs.com/compatible-mode/v1",
            "cn,https://dashscope.aliyuncs.com/compatible-mode/v1",
            "singapore,https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            "'',https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            "unknown,https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
    })
    void buildChatModelWithProviderOrder_shouldResolveDashScopeRegionMapping(String region, String expectedBaseUrl) {
        AgentAiServiceFactory factory = new AgentAiServiceFactory(
                mock(AgentLlmResolver.class),
                new ObjectMapper(),
                mock(RawHttpLogger.class),
                mock(AgentObservabilityService.class),
                mock(OpenRouterCostService.class)
        );
        ReflectionTestUtils.setField(factory, "openAiApiKey", "fallback-key");
        ReflectionTestUtils.setField(factory, "maxTokens", 1024);
        ReflectionTestUtils.setField(factory, "temperature", 0.6D);

        AgentLlmResolver.ResolvedLlm resolved = new AgentLlmResolver.ResolvedLlm(
                "dashscope",
                "",
                "qwen-plus",
                "dashscope-key",
                region
        );

        ChatLanguageModel model = factory.buildChatModelWithProviderOrder(resolved, java.util.List.of("fireworks"));

        assertInstanceOf(DashScopeChatModel.class, model);
        String baseUrl = (String) ReflectionTestUtils.getField(model, "baseUrl");
        assertEquals(expectedBaseUrl, baseUrl);
    }

    @Test
    void buildChatModelWithProviderOrder_shouldPreferResolvedBaseUrlOverRegion() {
        AgentAiServiceFactory factory = new AgentAiServiceFactory(
                mock(AgentLlmResolver.class),
                new ObjectMapper(),
                mock(RawHttpLogger.class),
                mock(AgentObservabilityService.class),
                mock(OpenRouterCostService.class)
        );
        ReflectionTestUtils.setField(factory, "openAiApiKey", "fallback-key");
        ReflectionTestUtils.setField(factory, "maxTokens", 1024);
        ReflectionTestUtils.setField(factory, "temperature", 0.6D);

        AgentLlmResolver.ResolvedLlm resolved = new AgentLlmResolver.ResolvedLlm(
                "dashscope",
                "https://custom-dashscope.example/compatible-mode/v1",
                "qwen-plus",
                "dashscope-key",
                "us"
        );

        ChatLanguageModel model = factory.buildChatModelWithProviderOrder(resolved, java.util.List.of());

        assertInstanceOf(DashScopeChatModel.class, model);
        String baseUrl = (String) ReflectionTestUtils.getField(model, "baseUrl");
        assertEquals("https://custom-dashscope.example/compatible-mode/v1", baseUrl);
    }
}

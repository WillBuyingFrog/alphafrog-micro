package world.willfrog.agent.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OpenRouterProviderRoutedChatModelTest {

    @Test
    void normalizeOpenRouterTokenLimit_shouldUseMaxTokensForProviderRouting() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", "moonshotai/kimi-k2.5");
        payload.put("max_completion_tokens", 512);

        OpenRouterProviderRoutedChatModel.normalizeOpenRouterTokenLimit(payload);

        assertFalse(payload.containsKey("max_completion_tokens"));
        assertEquals(512, payload.get("max_tokens"));
    }

    @Test
    void normalizeOpenRouterTokenLimit_shouldKeepExistingMaxTokens() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("max_tokens", 256);
        payload.put("max_completion_tokens", 512);

        OpenRouterProviderRoutedChatModel.normalizeOpenRouterTokenLimit(payload);

        assertFalse(payload.containsKey("max_completion_tokens"));
        assertEquals(256, payload.get("max_tokens"));
    }
}

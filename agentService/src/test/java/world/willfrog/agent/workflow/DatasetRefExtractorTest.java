package world.willfrog.agent.workflow;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetRefExtractorTest {

    @Test
    void registerFromJson_shouldRegisterSingularDatasetId() {
        Map<String, String> refs = new HashMap<>();
        int added = DatasetRefExtractor.registerFromJson(
                "{\"ok\":true,\"data\":{\"dataset_id\":\"aff1234567890abcdef\"}}",
                refs);
        assertEquals(1, added);
        assertTrue(refs.containsKey("aff1234567890abcdef"));
    }

    @Test
    void registerFromJson_shouldRegisterPluralDatasetIdsArray() {
        Map<String, String> refs = new HashMap<>();
        int added = DatasetRefExtractor.registerFromJson("""
                {"ok":true,"data":{"dataset_ids":["aff1111111111111111","aff2222222222222222"]}}
                """, refs);
        assertEquals(2, added);
        assertEquals(2, refs.size());
    }

    @Test
    void registerFromJson_shouldIgnoreMarkdownText() {
        Map<String, String> refs = new HashMap<>();
        int added = DatasetRefExtractor.registerFromJson(
                "| aff1234567890abcdef | 512400.SH |",
                refs);
        assertEquals(0, added);
        assertTrue(refs.isEmpty());
    }

    @Test
    void registerFromMessageHistory_shouldUseToolResultsOnly() {
        Map<String, String> refs = new HashMap<>();
        List<CompletedTodoInfo.ChatMessageSnapshot> history = List.of(
                CompletedTodoInfo.ChatMessageSnapshot.builder()
                        .role("assistant")
                        .content("| aff9999999999999999 |")
                        .build(),
                CompletedTodoInfo.ChatMessageSnapshot.builder()
                        .role("tool")
                        .content("{\"data\":{\"dataset_id\":\"aff1234567890abcdef\"}}")
                        .build()
        );
        int added = DatasetRefExtractor.registerFromMessageHistory(history, refs);
        assertEquals(1, added);
        assertFalse(refs.containsKey("aff9999999999999999"));
    }
}

package world.willfrog.agent.workflow;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetRefHandoffSupportTest {

    @Test
    void detect_v4StyleFourteenMentionedOneRegistered_shouldTriggerMismatch() {
        String runPrefix = "aff94fcdb8354c16be197e1701e3c0a3";
        StringBuilder text = new StringBuilder("Registered datasets:\n");
        Map<String, String> refs = new HashMap<>();
        for (int i = 0; i < 14; i++) {
            String id = runPrefix + "-etf-512400.SH-20250101-20251231-" + String.format("%08x", i);
            text.append(id).append('\n');
            if (i == 0) {
                refs.put(id, DatasetRefExtractor.sandboxPath(id));
            }
        }

        DatasetRefHandoffSupport.HandoffMismatch mismatch =
                DatasetRefHandoffSupport.detect(text.toString(), refs);

        assertNotNull(mismatch);
        assertEquals(1, mismatch.datasetRefsCount());
        assertEquals(14, mismatch.mentionedDatasetIdsCount());
        assertFalse(mismatch.missingDatasetIdsSample().isEmpty());
        assertTrue(mismatch.missingDatasetIdsSample().get(0).contains("-etf-512400.SH-"));
    }

    @Test
    void extractMentionedIds_shouldNotDedupeByRunIdPrefixOnly() {
        String id1 = "0123456789abcdef0123456789abcdef-etf-512400.SH-20250101-20251231-aaaaaaaa";
        String id2 = "0123456789abcdef0123456789abcdef-etf-159915.SZ-20250101-20251231-bbbbbbbb";
        var ids = DatasetRefHandoffSupport.extractMentionedIds(id1 + " " + id2);
        assertEquals(2, ids.size());
    }

    @Test
    void extractMentionedIds_shouldRecognizeNonAffRunIdPrefix() {
        String id = "0123456789abcdef0123456789abcdef-etf-512400.SH-20250101-20251231-12345678";
        var ids = DatasetRefHandoffSupport.extractMentionedIds("dataset: " + id);
        assertEquals(1, ids.size());
        assertTrue(ids.contains(id));
    }

    @Test
    void extractMentionedIds_shouldRecognizeUnknownRunIdPrefix() {
        String id = "unknown-etf-512400.SH-20250101-20251231-abcdef12";
        var ids = DatasetRefHandoffSupport.extractMentionedIds("dataset: " + id);
        assertEquals(1, ids.size());
        assertTrue(ids.contains(id));
    }

    @Test
    void extractMentionedIds_shouldNotMatchPartialSliceOfLongerHexPrefix() {
        // 33 位 hex 前缀不应被截成后 32 位合法 ID
        String tooLongPrefix = "0aff94fcdb8354c16be197e1701e3c0a3";
        String suffix = "-etf-512400.SH-20250101-20251231-12345678";
        var ids = DatasetRefHandoffSupport.extractMentionedIds(tooLongPrefix + suffix);
        assertTrue(ids.isEmpty());
    }
}

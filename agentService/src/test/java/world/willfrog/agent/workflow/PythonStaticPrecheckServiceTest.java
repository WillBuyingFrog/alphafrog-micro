package world.willfrog.agent.workflow;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonStaticPrecheckServiceTest {

    private final PythonStaticPrecheckService service = new PythonStaticPrecheckService();

    @Test
    void check_shouldFailWithMissingDatasetIdsWhenDatasetIdsEmpty() {
        PythonStaticPrecheckService.Result result = service.check("print(1)", "", Map.of());

        assertFalse(result.isPassed());
        assertEquals("MISSING_DATASET_IDS", result.getErrorCode());
    }

    @Test
    void check_shouldPassWhenCodeAndDatasetIdsPresent() {
        PythonStaticPrecheckService.Result result = service.check(
                "print(1)",
                "ds-1",
                Map.of()
        );

        assertTrue(result.isPassed());
    }

    @Test
    void check_shouldFailWithStaticPrecheckWhenForbiddenPathUsed() {
        PythonStaticPrecheckService.Result result = service.check(
                "open('/datasets/foo.csv')",
                "ds-1",
                Map.of()
        );

        assertFalse(result.isPassed());
        assertEquals("STATIC_PRECHECK_FAILED", result.getErrorCode());
    }
}

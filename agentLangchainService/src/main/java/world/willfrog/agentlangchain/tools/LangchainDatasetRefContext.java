package world.willfrog.agentlangchain.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-local dataset refs for the current LangChain linear todo execution.
 */
public final class LangchainDatasetRefContext {

    private static final ThreadLocal<Map<String, String>> DATASET_REFS = new ThreadLocal<>();

    private LangchainDatasetRefContext() {
    }

    public static void set(Map<String, String> datasetRefs) {
        DATASET_REFS.set(datasetRefs);
    }

    public static Map<String, String> snapshot() {
        Map<String, String> refs = DATASET_REFS.get();
        if (refs == null || refs.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(refs);
    }

    public static void clear() {
        DATASET_REFS.remove();
    }
}

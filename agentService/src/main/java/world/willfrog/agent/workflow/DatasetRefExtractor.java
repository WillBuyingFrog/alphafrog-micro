package world.willfrog.agent.workflow;

import java.util.List;
import java.util.Map;

/**
 * Agent-service dataset ref handoff (message history / execution records).
 */
final class DatasetRefExtractor {

    private DatasetRefExtractor() {
    }

    static int registerFromJson(String json, Map<String, String> datasetRefs) {
        return DatasetRefRegistry.registerFromJson(json, datasetRefs);
    }

    static int registerFromMessageHistory(List<CompletedTodoInfo.ChatMessageSnapshot> messageHistory,
                                          Map<String, String> datasetRefs) {
        if (messageHistory == null || messageHistory.isEmpty() || datasetRefs == null) {
            return 0;
        }
        int added = 0;
        for (CompletedTodoInfo.ChatMessageSnapshot snapshot : messageHistory) {
            if (snapshot == null || snapshot.getContent() == null || snapshot.getContent().isBlank()) {
                continue;
            }
            if (!"tool".equalsIgnoreCase(snapshot.getRole())) {
                continue;
            }
            added += registerFromJson(snapshot.getContent(), datasetRefs);
        }
        return added;
    }

    static int mergeRecordDatasetRefs(ReactTodoExecutor.TodoExecutionRecord record,
                                      Map<String, String> datasetRefs) {
        if (record == null || datasetRefs == null) {
            return 0;
        }
        int added = registerFromMessageHistory(record.getMessageHistory(), datasetRefs);
        added += registerFromJson(record.getOutput(), datasetRefs);
        return added;
    }

    static String sandboxPath(String datasetId) {
        return "/sandbox/input/" + datasetId;
    }
}

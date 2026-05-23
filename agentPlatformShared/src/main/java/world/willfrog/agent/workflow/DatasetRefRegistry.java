package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 从工具 JSON 结果提取 dataset ID 并注册到 {@code datasetRefs} 映射表（跨服务共享）。
 */
@Slf4j
public final class DatasetRefRegistry {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private DatasetRefRegistry() {
    }

    public static int registerFromJson(String json, Map<String, String> datasetRefs) {
        if (json == null || json.isBlank() || datasetRefs == null) {
            return 0;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            return registerFromDataNode(root.path("data"), datasetRefs);
        } catch (Exception e) {
            log.debug("No dataset ids in JSON payload: {}", e.getMessage());
            return 0;
        }
    }

    private static int registerFromDataNode(JsonNode data, Map<String, String> datasetRefs) {
        if (data == null || !data.isObject()) {
            return 0;
        }
        int added = 0;
        String datasetId = data.path("dataset_id").asText("");
        if (!datasetId.isBlank()) {
            added += registerId(datasetId, datasetRefs) ? 1 : 0;
        }
        JsonNode ids = data.path("dataset_ids");
        if (ids.isArray()) {
            for (JsonNode idNode : ids) {
                String id = idNode.asText("");
                if (!id.isBlank() && registerId(id, datasetRefs)) {
                    added++;
                }
            }
        } else if (ids.isTextual()) {
            for (String id : ids.asText("").split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isBlank() && registerId(trimmed, datasetRefs)) {
                    added++;
                }
            }
        }
        JsonNode results = data.path("results");
        if (results.isArray()) {
            for (JsonNode result : results) {
                added += registerFromDataNode(result.path("data"), datasetRefs);
            }
        }
        return added;
    }

    private static boolean registerId(String datasetId, Map<String, String> datasetRefs) {
        if (datasetId == null || datasetId.isBlank()) {
            return false;
        }
        String path = "/sandbox/input/" + datasetId;
        String existing = datasetRefs.putIfAbsent(datasetId, path);
        if (existing == null) {
            log.info("Registered dataset ref: {} -> {}", datasetId, path);
            return true;
        }
        return false;
    }
}

package world.willfrog.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatasetRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void datasetMeta_shouldDeserializeFromJson() throws Exception {
        String json = "{"
                + "\"datasetId\":\"dataset-1\","
                + "\"queryKey\":\"query-key-1\","
                + "\"type\":\"index_daily\","
                + "\"tsCode\":\"000300.SH\","
                + "\"startDate\":\"20250701\","
                + "\"endDate\":\"20251231\","
                + "\"columns\":[\"trade_date\",\"close\"],"
                + "\"columnsSignature\":\"trade_date,close\","
                + "\"rowCount\":126,"
                + "\"path\":\"/data/agent_datasets/dataset-1\","
                + "\"createdAt\":1739428200000,"
                + "\"lastAccessAt\":1739428300000,"
                + "\"hitCount\":2,"
                + "\"ttlSeconds\":604800,"
                + "\"expireAt\":1739999999999"
                + "}";

        DatasetRegistry.DatasetMeta meta = objectMapper.readValue(json, DatasetRegistry.DatasetMeta.class);

        assertEquals("dataset-1", meta.getDatasetId());
        assertEquals("query-key-1", meta.getQueryKey());
        assertEquals("index_daily", meta.getType());
        assertEquals(List.of("trade_date", "close"), meta.getColumns());
        assertEquals(126, meta.getRowCount());
    }
}

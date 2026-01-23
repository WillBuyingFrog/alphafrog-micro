package world.willfrog.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@Slf4j
public class DatasetWriter {

    @Value("${agent.tools.market-data.dataset.path:/data/agent_datasets}")
    private String datasetPath;

    @Value("${agent.tools.market-data.dataset.enabled:true}")
    private boolean enabled;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isEnabled() {
        return enabled;
    }

    public <T> String writeDataset(String prefix, String tsCode, String start, String end, 
                                   List<T> data, 
                                   List<String> headers, 
                                   Function<T, List<Object>> rowMapper) {
        if (!enabled) {
            return null;
        }

        ensureDirectory();

        String uuid = UUID.randomUUID().toString().substring(0, 8);
        // datasetId format: <prefix>-<tsCode>-<start>-<end>-<uuid>
        // Sanitize components to ensure valid filename
        String safeTsCode = tsCode.replaceAll("[^a-zA-Z0-9.]", "_");
        String datasetId = String.format("%s-%s-%s-%s-%s", prefix, safeTsCode, start, end, uuid);

        File datasetDir = new File(datasetPath, datasetId);
        if (!datasetDir.exists()) {
            datasetDir.mkdirs();
        }

        File csvFile = new File(datasetDir, datasetId + ".csv");
        File metaFile = new File(datasetDir, datasetId + ".meta.json");

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFile))) {
            // Write Header
            writer.write(String.join(",", headers));
            writer.newLine();

            // Write Data
            for (T item : data) {
                List<Object> row = rowMapper.apply(item);
                String line = row.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","));
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            log.error("Failed to write dataset CSV: " + datasetId, e);
            throw new RuntimeException("Failed to write dataset", e);
        }

        // Write Metadata
        try {
            DatasetMetadata meta = DatasetMetadata.builder()
                    .datasetId(datasetId)
                    .tsCode(tsCode)
                    .startDate(start)
                    .endDate(end)
                    .rowCount(data.size())
                    .columns(headers)
                    .format("csv")
                    .build();
            objectMapper.writeValue(metaFile, meta);
        } catch (IOException e) {
             log.error("Failed to write dataset Meta: " + datasetId, e);
        }

        return datasetId;
    }

    private void ensureDirectory() {
        File dir = new File(datasetPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    @Data
    @Builder
    public static class DatasetMetadata {
        private String datasetId;
        private String tsCode;
        private String startDate;
        private String endDate;
        private int rowCount;
        private List<String> columns;
        private String format;
    }
}

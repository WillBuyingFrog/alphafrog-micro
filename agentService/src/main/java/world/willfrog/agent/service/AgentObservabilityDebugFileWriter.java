package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentObservabilityDebugFileWriter {

    @Value("${agent.observability.debug-file.enabled:false}")
    private boolean enabled;

    @Value("${agent.observability.debug-file.path:/data/logs/agent-observability-debug.log}")
    private String path;

    private final ObjectMapper objectMapper;
    private final Object lock = new Object();
    private volatile boolean warned = false;

    public void write(String type, Map<String, Object> payload) {
        if (!enabled || payload == null) {
            return;
        }
        Path output = resolvePath();
        if (output == null) {
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("time", OffsetDateTime.now().toString());
        line.put("type", type == null ? "" : type);
        line.put("payload", payload);
        String text = safeWrite(line) + System.lineSeparator();
        synchronized (lock) {
            try {
                Files.writeString(
                        output,
                        text,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (Exception e) {
                warnOnce("Write observability debug file failed: " + e.getMessage(), e);
            }
        }
    }

    private Path resolvePath() {
        if (path == null || path.isBlank()) {
            warnOnce("Observability debug file path is blank", null);
            return null;
        }
        Path output = Paths.get(path).normalize();
        try {
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return output;
        } catch (Exception e) {
            warnOnce("Prepare observability debug file path failed: " + e.getMessage(), e);
            return null;
        }
    }

    private String safeWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void warnOnce(String message, Exception e) {
        if (warned) {
            return;
        }
        warned = true;
        if (e == null) {
            log.warn(message);
        } else {
            log.warn(message, e);
        }
    }
}


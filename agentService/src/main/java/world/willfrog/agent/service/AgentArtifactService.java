package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.entity.AgentRunEvent;
import world.willfrog.agent.mapper.AgentRunEventMapper;
import world.willfrog.alphafrogmicro.agent.idl.AgentArtifactMessage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentArtifactService {

    private static final Pattern DATASET_RESULT_PATTERN =
            Pattern.compile("DATASET_(?:CREATED|REUSED):\\s*([A-Za-z0-9._-]+)");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final AgentRunEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    @Value("${agent.tools.market-data.dataset.path:/data/agent_datasets}")
    private String datasetPath;

    @Value("${agent.artifact.retention-days.normal:7}")
    private int normalRetentionDays;

    @Value("${agent.artifact.retention-days.admin:30}")
    private int adminRetentionDays;

    @Value("${agent.artifact.download.max-bytes:10485760}")
    private long downloadMaxBytes;

    public String extractRunId(String artifactId) {
        ArtifactRef ref = decodeArtifactId(artifactId);
        return ref.runId();
    }

    public List<AgentArtifactMessage> listArtifacts(AgentRun run, boolean isAdmin) {
        List<ResolvedArtifact> artifacts = resolveArtifacts(run, isAdmin);
        List<AgentArtifactMessage> result = new ArrayList<>();
        for (ResolvedArtifact artifact : artifacts) {
            result.add(AgentArtifactMessage.newBuilder()
                    .setArtifactId(artifact.getArtifactId())
                    .setType(artifact.getType())
                    .setName(artifact.getName())
                    .setContentType(artifact.getContentType())
                    .setUrl("/api/agent/artifacts/" + artifact.getArtifactId() + "/download")
                    .setMetaJson(artifact.getMetaJson())
                    .setCreatedAt(formatTime(artifact.getCreatedAt()))
                    .setExpiresAtMillis(artifact.getExpiresAtMillis())
                    .build());
        }
        return result;
    }

    public ArtifactContent loadArtifact(AgentRun run, boolean isAdmin, String artifactId) {
        List<ResolvedArtifact> artifacts = resolveArtifacts(run, isAdmin);
        ResolvedArtifact target = artifacts.stream()
                .filter(item -> Objects.equals(item.getArtifactId(), artifactId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("artifact not found"));

        if (target.getInlineContent() != null) {
            byte[] bytes = target.getInlineContent().getBytes(StandardCharsets.UTF_8);
            return new ArtifactContent(target.getArtifactId(), target.getName(), target.getContentType(), bytes);
        }
        if (target.getFilePath() == null) {
            throw new IllegalArgumentException("artifact source not found");
        }

        try {
            long size = Files.size(target.getFilePath());
            if (downloadMaxBytes > 0 && size > downloadMaxBytes) {
                throw new IllegalStateException("artifact too large to download");
            }
            byte[] bytes = Files.readAllBytes(target.getFilePath());
            return new ArtifactContent(target.getArtifactId(), target.getName(), target.getContentType(), bytes);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("read artifact failed", e);
        }
    }

    private List<ResolvedArtifact> resolveArtifacts(AgentRun run, boolean isAdmin) {
        List<AgentRunEvent> events = eventMapper.listByRunId(run.getId());
        ParsedEvents parsed = parseEvents(events);

        List<PythonInvocation> selectedInvocations = selectInvocations(parsed.invocations(), isAdmin);
        LinkedHashSet<String> selectedDatasetIds = new LinkedHashSet<>();
        for (PythonInvocation invocation : selectedInvocations) {
            selectedDatasetIds.addAll(invocation.datasetIds());
        }
        if (selectedDatasetIds.isEmpty()) {
            if (isAdmin) {
                selectedDatasetIds.addAll(parsed.fallbackDatasetIds());
            } else if (!parsed.fallbackDatasetIds().isEmpty()) {
                selectedDatasetIds.add(parsed.fallbackDatasetIds().get(parsed.fallbackDatasetIds().size() - 1));
            }
        }

        List<ResolvedArtifact> artifacts = new ArrayList<>();
        for (PythonInvocation invocation : selectedInvocations) {
            if (invocation.code() == null || invocation.code().isBlank()) {
                continue;
            }
            long createdAtMillis = toMillis(invocation.createdAt(), run);
            long expiresAtMillis = calcExpiresAtMillis(createdAtMillis, isAdmin);
            if (isExpired(expiresAtMillis)) {
                continue;
            }
            String artifactId = encodeArtifactId("script", run.getId(), String.valueOf(invocation.seq()));
            Map<String, Object> meta = new HashMap<>();
            meta.put("kind", "python_script");
            meta.put("scope", isAdmin ? "admin" : "normal");
            meta.put("seq", invocation.seq());
            meta.put("final_candidate", Objects.equals(invocation, selectedInvocations.get(selectedInvocations.size() - 1)));
            artifacts.add(ResolvedArtifact.builder()
                    .artifactId(artifactId)
                    .type("python_script")
                    .name("run-" + run.getId() + "-seq-" + invocation.seq() + ".py")
                    .contentType("text/x-python")
                    .metaJson(writeJson(meta))
                    .createdAt(toOffsetDateTime(createdAtMillis))
                    .expiresAtMillis(expiresAtMillis)
                    .inlineContent(invocation.code())
                    .build());
        }

        Path baseDir = Paths.get(datasetPath);
        for (String datasetId : selectedDatasetIds) {
            if (datasetId == null || datasetId.isBlank()) {
                continue;
            }
            Path datasetDir = baseDir.resolve(datasetId).normalize();
            if (!datasetDir.startsWith(baseDir)) {
                continue;
            }
            Path csvFile = datasetDir.resolve(datasetId + ".csv");
            addDatasetFileArtifact(artifacts, run.getId(), datasetId, csvFile, "dataset_csv", "text/csv", isAdmin);
            Path metaFile = datasetDir.resolve(datasetId + ".meta.json");
            addDatasetFileArtifact(artifacts, run.getId(), datasetId, metaFile, "dataset_meta", "application/json", isAdmin);
        }

        artifacts.sort((a, b) -> Long.compare(
                b.getCreatedAt() == null ? 0L : b.getCreatedAt().toInstant().toEpochMilli(),
                a.getCreatedAt() == null ? 0L : a.getCreatedAt().toInstant().toEpochMilli()
        ));
        return artifacts;
    }

    private void addDatasetFileArtifact(List<ResolvedArtifact> artifacts,
                                        String runId,
                                        String datasetId,
                                        Path filePath,
                                        String type,
                                        String contentType,
                                        boolean isAdmin) {
        try {
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return;
            }
            long createdAtMillis = Files.getLastModifiedTime(filePath).toMillis();
            long expiresAtMillis = calcExpiresAtMillis(createdAtMillis, isAdmin);
            if (isExpired(expiresAtMillis)) {
                return;
            }
            String artifactId = encodeArtifactId(type, runId, datasetId);
            Map<String, Object> meta = new HashMap<>();
            meta.put("kind", type);
            meta.put("dataset_id", datasetId);
            meta.put("scope", isAdmin ? "admin" : "normal");
            artifacts.add(ResolvedArtifact.builder()
                    .artifactId(artifactId)
                    .type(type)
                    .name(filePath.getFileName().toString())
                    .contentType(contentType)
                    .metaJson(writeJson(meta))
                    .createdAt(toOffsetDateTime(createdAtMillis))
                    .expiresAtMillis(expiresAtMillis)
                    .filePath(filePath)
                    .build());
        } catch (Exception e) {
            log.warn("Resolve dataset artifact failed: runId={}, datasetId={}, file={}", runId, datasetId, filePath, e);
        }
    }

    private ParsedEvents parseEvents(List<AgentRunEvent> events) {
        List<PythonInvocation> invocations = new ArrayList<>();
        LinkedHashSet<String> fallbackDatasetIds = new LinkedHashSet<>();
        for (AgentRunEvent event : events) {
            if (event == null || event.getEventType() == null) {
                continue;
            }
            Map<String, Object> payload = readJsonMap(event.getPayloadJson());
            if ("TOOL_CALL_STARTED".equals(event.getEventType())) {
                String toolName = readAsString(payload.get("tool_name"));
                if (!"executePython".equals(toolName)) {
                    continue;
                }
                Map<String, Object> params = readNestedMap(payload.get("parameters"));
                String code = firstNonBlank(
                        readAsString(params.get("code")),
                        readAsString(params.get("arg0"))
                );
                String datasetId = firstNonBlank(
                        readAsString(params.get("dataset_id")),
                        readAsString(params.get("datasetId")),
                        readAsString(params.get("arg1"))
                );
                String datasetIds = firstNonBlank(
                        readAsString(params.get("dataset_ids")),
                        readAsString(params.get("datasetIds")),
                        readAsString(params.get("arg2"))
                );
                List<String> mergedDatasetIds = mergeDatasetIds(datasetId, datasetIds);
                invocations.add(new PythonInvocation(
                        event.getSeq() == null ? 0 : event.getSeq(),
                        event.getCreatedAt(),
                        code == null ? "" : code,
                        mergedDatasetIds
                ));
                continue;
            }
            if ("TOOL_CALL_FINISHED".equals(event.getEventType())) {
                String preview = readAsString(payload.get("result_preview"));
                Matcher matcher = DATASET_RESULT_PATTERN.matcher(preview == null ? "" : preview);
                while (matcher.find()) {
                    String datasetId = matcher.group(1);
                    if (datasetId != null && !datasetId.isBlank()) {
                        fallbackDatasetIds.add(datasetId.trim());
                    }
                }
            }
        }
        return new ParsedEvents(invocations, new ArrayList<>(fallbackDatasetIds));
    }

    private List<PythonInvocation> selectInvocations(List<PythonInvocation> invocations, boolean isAdmin) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        if (isAdmin) {
            return invocations;
        }
        return List.of(invocations.get(invocations.size() - 1));
    }

    private Map<String, Object> readNestedMap(Object obj) {
        if (obj == null) {
            return Map.of();
        }
        if (obj instanceof Map<?, ?> raw) {
            Map<String, Object> m = new HashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                m.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return m;
        }
        if (obj instanceof String text && !text.isBlank()) {
            return readJsonMap(text);
        }
        return Map.of();
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<?, ?> map = objectMapper.readValue(json, Map.class);
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<String> mergeDatasetIds(String primary, String others) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (primary != null && !primary.isBlank()) {
            ids.add(primary.trim());
        }
        if (others != null && !others.isBlank()) {
            String normalized = others.trim();
            if (normalized.startsWith("[") && normalized.endsWith("]")) {
                normalized = normalized.substring(1, normalized.length() - 1);
            }
            String[] parts = normalized.split(",");
            for (String part : parts) {
                String id = part == null ? "" : part.trim();
                if (id.startsWith("\"") && id.endsWith("\"") && id.length() >= 2) {
                    id = id.substring(1, id.length() - 1).trim();
                }
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private String readAsString(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value);
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return "";
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    private String encodeArtifactId(String type, String runId, String ref) {
        String raw = type + "|" + runId + "|" + ref;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private ArtifactRef decodeArtifactId(String artifactId) {
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifact_id is required");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(artifactId), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 3);
            if (parts.length != 3 || parts[1].isBlank()) {
                throw new IllegalArgumentException("invalid artifact id");
            }
            return new ArtifactRef(parts[0], parts[1], parts[2]);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid artifact id");
        }
    }

    private long calcExpiresAtMillis(long createdAtMillis, boolean isAdmin) {
        int days = isAdmin ? adminRetentionDays : normalRetentionDays;
        if (days <= 0) {
            return Long.MAX_VALUE;
        }
        return createdAtMillis + days * 24L * 60L * 60L * 1000L;
    }

    private boolean isExpired(long expiresAtMillis) {
        return expiresAtMillis != Long.MAX_VALUE && System.currentTimeMillis() > expiresAtMillis;
    }

    private long toMillis(OffsetDateTime time, AgentRun run) {
        if (time != null) {
            return time.toInstant().toEpochMilli();
        }
        if (run != null && run.getUpdatedAt() != null) {
            return run.getUpdatedAt().toInstant().toEpochMilli();
        }
        return System.currentTimeMillis();
    }

    private OffsetDateTime toOffsetDateTime(long epochMillis) {
        return OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }

    private String formatTime(OffsetDateTime time) {
        if (time == null) {
            return "";
        }
        return TIME_FORMATTER.format(time);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private record ParsedEvents(List<PythonInvocation> invocations, List<String> fallbackDatasetIds) {
    }

    private record PythonInvocation(int seq, OffsetDateTime createdAt, String code, List<String> datasetIds) {
    }

    private record ArtifactRef(String type, String runId, String ref) {
    }

    @Getter
    @Builder
    private static class ResolvedArtifact {
        private String artifactId;
        private String type;
        private String name;
        private String contentType;
        private String metaJson;
        private OffsetDateTime createdAt;
        private long expiresAtMillis;
        private String inlineContent;
        private Path filePath;
    }

    public record ArtifactContent(String artifactId, String filename, String contentType, byte[] content) {
    }
}

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
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
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
    private static final Pattern DATASET_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final AgentRunEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    @Value("${agent.tools.market-data.dataset.path:/data/agent_datasets}")
    private String datasetPath;

    @Value("${agent.artifact.storage.path:/data/agent_artifacts}")
    private String artifactStoragePath;

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
        selectedDatasetIds.addAll(parsed.fallbackDatasetIds());

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
            String artifactId = encodeArtifactId("script", run.getId(), invocation.ref());
            Path scriptFile = snapshotPythonScript(run.getId(), invocation.ref(), invocation.code(), createdAtMillis);
            if (scriptFile == null) {
                continue;
            }
            Map<String, Object> meta = new HashMap<>();
            meta.put("kind", "python_script");
            meta.put("scope", isAdmin ? "admin" : "normal");
            meta.put("source", invocation.source());
            meta.put("seq", invocation.seq());
            meta.put("success", invocation.success());
            artifacts.add(ResolvedArtifact.builder()
                    .artifactId(artifactId)
                    .type("python_script")
                    .name(scriptFile.getFileName().toString())
                    .contentType("text/x-python")
                    .metaJson(writeJson(meta))
                    .createdAt(toOffsetDateTime(createdAtMillis))
                    .expiresAtMillis(expiresAtMillis)
                    .filePath(scriptFile)
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
            Path copiedFile = snapshotDatasetFile(runId, datasetId, filePath);
            if (copiedFile == null) {
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
                    .name(copiedFile.getFileName().toString())
                    .contentType(contentType)
                    .metaJson(writeJson(meta))
                    .createdAt(toOffsetDateTime(createdAtMillis))
                    .expiresAtMillis(expiresAtMillis)
                    .filePath(copiedFile)
                    .build());
        } catch (Exception e) {
            log.warn("Resolve dataset artifact failed: runId={}, datasetId={}, file={}", runId, datasetId, filePath, e);
        }
    }

    private ParsedEvents parseEvents(List<AgentRunEvent> events) {
        List<MutableInvocation> invocations = new ArrayList<>();
        Map<String, MutableInvocation> invocationByRef = new HashMap<>();
        LinkedHashSet<String> fallbackDatasetIds = new LinkedHashSet<>();
        List<String> pendingToolRefs = new ArrayList<>();
        Map<String, String> pendingParallelRefsByTask = new HashMap<>();
        Map<String, Map<String, Object>> parallelExecuteArgsByTask = new HashMap<>();
        for (AgentRunEvent event : events) {
            if (event == null || event.getEventType() == null) {
                continue;
            }
            Map<String, Object> payload = readJsonMap(event.getPayloadJson());
            String eventType = event.getEventType();
            collectDatasetIds(fallbackDatasetIds, readAsString(payload.get("result_preview")));
            collectDatasetIds(fallbackDatasetIds, readAsString(payload.get("output_preview")));

            if ("PLAN_CREATED".equals(eventType)) {
                collectParallelExecutePythonArgs(payload, parallelExecuteArgsByTask);
                continue;
            }

            if ("TOOL_CALL_STARTED".equals(eventType)) {
                String toolName = readAsString(payload.get("tool_name"));
                if (!"executePython".equals(toolName)) {
                    continue;
                }
                Map<String, Object> params = readNestedMap(payload.get("parameters"));
                MutableInvocation invocation = createInvocation(
                        "tool-" + safeSeq(event),
                        safeSeq(event),
                        event.getCreatedAt(),
                        "TOOL_CALL",
                        extractCode(params),
                        extractDatasetIds(params),
                        null
                );
                invocations.add(invocation);
                invocationByRef.put(invocation.ref, invocation);
                pendingToolRefs.add(invocation.ref);
                continue;
            }

            if ("TOOL_CALL_FINISHED".equals(eventType)) {
                String toolName = readAsString(payload.get("tool_name"));
                if (!"executePython".equals(toolName)) {
                    continue;
                }
                String preview = readAsString(payload.get("result_preview"));
                if (!pendingToolRefs.isEmpty()) {
                    String ref = pendingToolRefs.remove(0);
                    MutableInvocation invocation = invocationByRef.get(ref);
                    if (invocation != null) {
                        invocation.success = toNullableBoolean(payload.get("success"));
                        if (invocation.success == null) {
                            invocation.success = inferPythonSuccess(preview);
                        }
                        invocation.datasetIds.addAll(extractDatasetIdsFromText(preview));
                    }
                }
                continue;
            }

            if ("PARALLEL_TASK_STARTED".equals(eventType)) {
                String toolName = readAsString(payload.get("tool"));
                if (!"executePython".equals(toolName)) {
                    continue;
                }
                String taskId = readAsString(payload.get("task_id"));
                Map<String, Object> taskArgs = parallelExecuteArgsByTask.getOrDefault(taskId, Map.of());
                MutableInvocation invocation = createInvocation(
                        "parallel-" + taskId + "-" + safeSeq(event),
                        safeSeq(event),
                        event.getCreatedAt(),
                        "PARALLEL_TASK",
                        extractCode(taskArgs),
                        extractDatasetIds(taskArgs),
                        null
                );
                invocations.add(invocation);
                invocationByRef.put(invocation.ref, invocation);
                pendingParallelRefsByTask.put(taskId, invocation.ref);
                continue;
            }

            if ("PARALLEL_TASK_FINISHED".equals(eventType)) {
                String taskId = readAsString(payload.get("task_id"));
                String preview = readAsString(payload.get("output_preview"));
                String ref = pendingParallelRefsByTask.remove(taskId);
                if (ref != null) {
                    MutableInvocation invocation = invocationByRef.get(ref);
                    if (invocation != null) {
                        invocation.success = toNullableBoolean(payload.get("success"));
                        if (invocation.success == null) {
                            invocation.success = inferPythonSuccess(preview);
                        }
                        invocation.datasetIds.addAll(extractDatasetIdsFromText(preview));
                    }
                }
                continue;
            }

            if ("SUB_AGENT_PYTHON_REFINED".equals(eventType)) {
                List<Map<String, Object>> traces = readMapList(payload.get("traces"));
                String taskId = readAsString(payload.get("task_id"));
                String stepIndex = readAsString(payload.get("step_index"));
                for (Map<String, Object> trace : traces) {
                    String code = firstNonBlank(
                            readAsString(trace.get("code")),
                            readAsString(trace.get("code_preview"))
                    );
                    Map<String, Object> runArgs = readNestedMap(
                            trace.get("run_args"),
                            trace.get("run_args_preview")
                    );
                    LinkedHashSet<String> datasetIds = new LinkedHashSet<>(extractDatasetIds(runArgs));
                    String outputPreview = readAsString(trace.get("output_preview"));
                    datasetIds.addAll(extractDatasetIdsFromText(outputPreview));

                    int attempt = toInt(trace.get("attempt"), 0);
                    MutableInvocation invocation = createInvocation(
                            "subtrace-" + taskId + "-" + stepIndex + "-" + attempt + "-" + safeSeq(event),
                            safeSeq(event),
                            event.getCreatedAt(),
                            "SUB_AGENT_TRACE",
                            code,
                            datasetIds,
                            toNullableBoolean(trace.get("success"))
                    );
                    invocations.add(invocation);
                }
            }
        }

        List<PythonInvocation> stableInvocations = new ArrayList<>();
        for (MutableInvocation invocation : invocations) {
            if (invocation.code == null || invocation.code.isBlank()) {
                continue;
            }
            stableInvocations.add(new PythonInvocation(
                    invocation.ref,
                    invocation.seq,
                    invocation.createdAt,
                    invocation.code,
                    new ArrayList<>(invocation.datasetIds),
                    invocation.success,
                    invocation.source
            ));
        }
        return new ParsedEvents(stableInvocations, new ArrayList<>(fallbackDatasetIds));
    }

    private List<PythonInvocation> selectInvocations(List<PythonInvocation> invocations, boolean isAdmin) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        if (isAdmin) {
            return invocations;
        }
        List<PythonInvocation> success = new ArrayList<>();
        for (PythonInvocation invocation : invocations) {
            if (Boolean.TRUE.equals(invocation.success())) {
                success.add(invocation);
            }
        }
        return success;
    }

    private MutableInvocation createInvocation(String ref,
                                               int seq,
                                               OffsetDateTime createdAt,
                                               String source,
                                               String code,
                                               LinkedHashSet<String> datasetIds,
                                               Boolean success) {
        MutableInvocation invocation = new MutableInvocation();
        invocation.ref = ref;
        invocation.seq = seq;
        invocation.createdAt = createdAt;
        invocation.source = source;
        invocation.code = code == null ? "" : code;
        invocation.datasetIds = datasetIds == null ? new LinkedHashSet<>() : datasetIds;
        invocation.success = success;
        return invocation;
    }

    private void collectParallelExecutePythonArgs(Map<String, Object> payload,
                                                  Map<String, Map<String, Object>> parallelExecuteArgsByTask) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        Map<String, Object> plan = readNestedMap(payload.get("plan"));
        if (plan.isEmpty()) {
            return;
        }
        for (Map<String, Object> task : readMapList(plan.get("tasks"))) {
            if (!"executePython".equals(readAsString(task.get("tool")))) {
                continue;
            }
            String taskId = readAsString(task.get("id"));
            if (taskId.isBlank()) {
                continue;
            }
            parallelExecuteArgsByTask.put(taskId, readNestedMap(task.get("args")));
        }
    }

    private Path snapshotPythonScript(String runId, String ref, String code, long createdAtMillis) {
        if (runId == null || runId.isBlank() || code == null) {
            return null;
        }
        try {
            Path targetDir = resolveRunArtifactDir(runId).resolve("scripts");
            Files.createDirectories(targetDir);
            Path targetFile = targetDir.resolve(sanitizeFileName(ref) + ".py");
            Files.writeString(targetFile, code, StandardCharsets.UTF_8);
            if (createdAtMillis > 0) {
                Files.setLastModifiedTime(targetFile, FileTime.fromMillis(createdAtMillis));
            }
            return targetFile;
        } catch (Exception e) {
            log.warn("Snapshot python script failed: runId={}, ref={}", runId, ref, e);
            return null;
        }
    }

    private Path snapshotDatasetFile(String runId, String datasetId, Path sourceFile) {
        if (runId == null || runId.isBlank() || datasetId == null || datasetId.isBlank()) {
            return null;
        }
        try {
            Path targetDir = resolveRunArtifactDir(runId).resolve("datasets").resolve(datasetId);
            Files.createDirectories(targetDir);
            Path targetFile = targetDir.resolve(sourceFile.getFileName().toString());
            if (!Files.exists(targetFile)) {
                Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            } else {
                long srcMtime = Files.getLastModifiedTime(sourceFile).toMillis();
                long dstMtime = Files.getLastModifiedTime(targetFile).toMillis();
                if (srcMtime > dstMtime) {
                    Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return targetFile;
        } catch (Exception e) {
            log.warn("Snapshot dataset file failed: runId={}, datasetId={}, source={}", runId, datasetId, sourceFile, e);
            return null;
        }
    }

    private Path resolveRunArtifactDir(String runId) {
        Path baseDir = Paths.get(artifactStoragePath).normalize();
        Path runDir = baseDir.resolve(runId).normalize();
        if (!runDir.startsWith(baseDir)) {
            throw new IllegalArgumentException("invalid run id path");
        }
        return runDir;
    }

    private String sanitizeFileName(String value) {
        if (value == null || value.isBlank()) {
            return "artifact";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void collectDatasetIds(LinkedHashSet<String> target, String text) {
        if (target == null || text == null || text.isBlank()) {
            return;
        }
        target.addAll(extractDatasetIdsFromText(text));
    }

    private LinkedHashSet<String> extractDatasetIds(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return mergeDatasetIds(
                firstNonBlank(
                        readAsString(args.get("dataset_id")),
                        readAsString(args.get("datasetId")),
                        readAsString(args.get("arg1"))
                ),
                firstNonBlank(
                        readAsString(args.get("dataset_ids")),
                        readAsString(args.get("datasetIds")),
                        readAsString(args.get("arg2"))
                )
        );
    }

    private String extractCode(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        return firstNonBlank(
                readAsString(args.get("code")),
                readAsString(args.get("arg0"))
        );
    }

    private LinkedHashSet<String> extractDatasetIdsFromText(String text) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return ids;
        }
        Matcher matcher = DATASET_RESULT_PATTERN.matcher(text);
        while (matcher.find()) {
            String datasetId = matcher.group(1);
            if (datasetId != null && !datasetId.isBlank()) {
                String cleaned = datasetId.trim();
                if (DATASET_ID_PATTERN.matcher(cleaned).matches()) {
                    ids.add(cleaned);
                }
            }
        }
        return ids;
    }

    private Boolean toNullableBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return false;
        }
        return null;
    }

    private boolean inferPythonSuccess(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        if (output.startsWith("Tool invocation error")) {
            return false;
        }
        if (output.startsWith("Tool Execution Error")) {
            return false;
        }
        if (output.startsWith("Failed to create task")) {
            return false;
        }
        if (output.startsWith("Task FAILED")) {
            return false;
        }
        if (output.startsWith("Task CANCELED")) {
            return false;
        }
        if (output.startsWith("Task PENDING (Timeout)")) {
            return false;
        }
        if (output.startsWith("Exit Code:")) {
            return false;
        }
        return !output.contains("STDERR:");
    }

    private int safeSeq(AgentRunEvent event) {
        return event == null || event.getSeq() == null ? 0 : event.getSeq();
    }

    private int toInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
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

    private Map<String, Object> readNestedMap(Object... candidates) {
        if (candidates == null || candidates.length == 0) {
            return Map.of();
        }
        for (Object candidate : candidates) {
            Map<String, Object> parsed = readNestedMap(candidate);
            if (!parsed.isEmpty()) {
                return parsed;
            }
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

    private LinkedHashSet<String> mergeDatasetIds(String primary, String others) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (primary != null && !primary.isBlank()) {
            String cleaned = primary.trim();
            if (DATASET_ID_PATTERN.matcher(cleaned).matches()) {
                ids.add(cleaned);
            }
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
                if (!id.isBlank() && DATASET_ID_PATTERN.matcher(id).matches()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private List<Map<String, Object>> readMapList(Object obj) {
        if (obj == null) {
            return List.of();
        }
        if (obj instanceof List<?> rawList) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : rawList) {
                Map<String, Object> map = readNestedMap(item);
                if (!map.isEmpty()) {
                    result.add(map);
                }
            }
            return result;
        }
        return List.of();
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

    private record PythonInvocation(String ref,
                                    int seq,
                                    OffsetDateTime createdAt,
                                    String code,
                                    List<String> datasetIds,
                                    Boolean success,
                                    String source) {
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
        private Path filePath;
    }

    private static class MutableInvocation {
        private String ref;
        private int seq;
        private OffsetDateTime createdAt;
        private String source;
        private String code;
        private LinkedHashSet<String> datasetIds = new LinkedHashSet<>();
        private Boolean success;
    }

    public record ArtifactContent(String artifactId, String filename, String contentType, byte[] content) {
    }
}

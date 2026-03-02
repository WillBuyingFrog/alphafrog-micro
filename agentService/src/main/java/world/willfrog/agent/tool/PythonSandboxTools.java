package world.willfrog.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class PythonSandboxTools {
    private static final int PENDING_EXTRA_WAIT_SECONDS = 90;
    private static final int POLL_INTERVAL_MS = 1000;

    @DubboReference
    private PythonSandboxService pythonSandboxService;

    private final ObjectMapper objectMapper;

    public PythonSandboxTools(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Tool("Execute Python code in a secure sandbox. REQUIRED: code, dataset_ids. OPTIONAL: libraries (comma-separated, e.g. 'numpy,pandas'), timeout_seconds. Dataset files are mounted under /sandbox/input/<dataset_id>/ (default: <dataset_id>.csv and <dataset_id>.meta.json). Multiple datasets: use comma-separated dataset_ids (e.g., 'ds1,ds2,ds3'). Runtime preinstalled: numpy==2.4.1, pandas==2.3.3, matplotlib==3.10.8, scipy==1.17.0. Service stack: fastapi==0.128.0, uvicorn[standard]==0.40.0, pydantic==2.12.5, llm-sandbox[docker]==0.3.33. Please prioritize using the preinstalled runtime libraries to reduce latency.")
    public String executePython(String code, String dataset_ids, String libraries, Integer timeout_seconds) {
        try {
            String[] parsedDatasetIds = parseDatasetIds(dataset_ids);
            if (parsedDatasetIds.length == 0) {
                return fail("executePython", "MISSING_DATASET_IDS", "dataset_ids is required and cannot be empty", Map.of());
            }
            String primaryDatasetId = parsedDatasetIds[0];
            log.info("Executing python task for datasets: primary={}, total={}", primaryDatasetId, parsedDatasetIds.length);

            ExecuteRequest.Builder requestBuilder = ExecuteRequest.newBuilder()
                    .setCode(nvl(code))
                    .setDatasetId(primaryDatasetId);

            // Add all datasets (including primary for consistency)
            for (String datasetId : parsedDatasetIds) {
                requestBuilder.addDatasetIds(datasetId);
            }

            if (libraries != null && !libraries.isBlank()) {
                for (String lib : libraries.split(",")) {
                    String normalized = lib == null ? "" : lib.trim();
                    if (!normalized.isBlank()) {
                        requestBuilder.addLibraries(normalized);
                    }
                }
            }

            int timeout = (timeout_seconds != null && timeout_seconds > 0) ? timeout_seconds : 30;
            requestBuilder.setTimeoutSeconds(timeout);

            ExecuteResponse createResp = pythonSandboxService.createTask(requestBuilder.build());
            if (createResp.getError() != null && !createResp.getError().isEmpty()) {
                return fail("executePython", "CREATE_TASK_FAILED", "Failed to create python sandbox task", Map.of(
                        "message", createResp.getError(),
                        "dataset_id", primaryDatasetId
                ));
            }

            String taskId = createResp.getTaskId();
            log.info("Task created: {}", taskId);

            long maxWaitMs = timeout * 1000L + 5000;
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < maxWaitMs) {
                TaskStatusResponse statusResp = getTaskStatus(taskId);
                String terminal = terminalOutput(taskId, statusResp);
                if (terminal != null) {
                    return terminal;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return fail("executePython", "INTERRUPTED", "Task polling interrupted", Map.of("task_id", taskId));
                }
            }

            long extraWaitMs = PENDING_EXTRA_WAIT_SECONDS * 1000L;
            long extraStart = System.currentTimeMillis();
            while (System.currentTimeMillis() - extraStart < extraWaitMs) {
                TaskStatusResponse statusResp = getTaskStatus(taskId);
                String terminal = terminalOutput(taskId, statusResp);
                if (terminal != null) {
                    return terminal;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return fail("executePython", "INTERRUPTED", "Task polling interrupted", Map.of("task_id", taskId));
                }
            }

            return fail("executePython", "TIMEOUT", "Task pending after timeout window", Map.of("task_id", taskId));
        } catch (Exception e) {
            log.error("Execute python tool error", e);
            return fail("executePython", "TOOL_ERROR", "Python sandbox invocation error", Map.of("message", nvl(e.getMessage())));
        }
    }

    private TaskStatusResponse getTaskStatus(String taskId) {
        return pythonSandboxService.getTaskStatus(
                GetTaskStatusRequest.newBuilder().setTaskId(taskId).build()
        );
    }

    private String terminalOutput(String taskId, TaskStatusResponse statusResp) {
        String status = statusResp.getStatus();
        if ("SUCCEEDED".equals(status)) {
            TaskResultResponse result = pythonSandboxService.getTaskResult(
                    GetTaskResultRequest.newBuilder().setTaskId(taskId).build()
            );
            return formatResult(taskId, status, result);
        }
        if ("FAILED".equals(status)) {
            return fail("executePython", "TASK_FAILED", "Task failed", Map.of(
                    "task_id", taskId,
                    "status", status,
                    "message", nvl(statusResp.getError())
            ));
        }
        if ("CANCELED".equals(status)) {
            return fail("executePython", "TASK_CANCELED", "Task canceled", Map.of(
                    "task_id", taskId,
                    "status", status
            ));
        }
        if ("NOT_FOUND".equals(status)) {
            return fail("executePython", "TASK_NOT_FOUND", "Task not found", Map.of(
                    "task_id", taskId,
                    "status", status
            ));
        }
        return null;
    }

    private String[] parseDatasetIds(String datasetIds) {
        if (datasetIds == null) {
            return new String[0];
        }
        String trimmed = datasetIds.trim();
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return java.util.Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .map(item -> {
                    String id = item;
                    if (id.startsWith("\"") && id.endsWith("\"") && id.length() >= 2) {
                        id = id.substring(1, id.length() - 1).trim();
                    }
                    return id;
                })
                .filter(s -> !s.isEmpty())
                .distinct()
                .toArray(String[]::new);
    }

    private String formatResult(String taskId, String status, TaskResultResponse result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", taskId);
        data.put("status", status);
        data.put("exit_code", result.getExitCode());
        data.put("stdout", nvl(result.getStdout()));
        data.put("stderr", nvl(result.getStderr()));
        data.put("dataset_dir", nvl(result.getDatasetDir()));

        if (result.getExitCode() == 0) {
            return ok("executePython", data);
        }

        return fail("executePython", "NON_ZERO_EXIT", "Python execution finished with non-zero exit code", Map.of(
                "task_id", taskId,
                "status", status,
                "exit_code", result.getExitCode(),
                "stderr", nvl(result.getStderr())
        ), data);
    }

    private String ok(String tool, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("tool", tool);
        payload.put("data", data == null ? Map.of() : data);
        payload.put("error", null);
        return writeJson(payload);
    }

    private String fail(String tool, String code, String message, Map<String, Object> details) {
        return fail(tool, code, message, details, Map.of());
    }

    private String fail(String tool,
                        String code,
                        String message,
                        Map<String, Object> details,
                        Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("tool", tool);
        payload.put("data", data == null ? Map.of() : data);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", nvl(code));
        err.put("message", nvl(message));
        err.put("details", details == null ? Map.of() : details);
        payload.put("error", err);
        return writeJson(payload);
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"ok\":false,\"tool\":\"executePython\",\"error\":{\"code\":\"JSON_SERIALIZE_ERROR\",\"message\":\"" + escapeJson(nvl(e.getMessage())) + "\"}}";
        }
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private String escapeJson(String text) {
        return nvl(text)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}

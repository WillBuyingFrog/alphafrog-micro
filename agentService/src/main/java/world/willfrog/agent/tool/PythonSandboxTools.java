package world.willfrog.agent.tool;

import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class PythonSandboxTools {
    private static final int PENDING_EXTRA_WAIT_SECONDS = 90;
    private static final int POLL_INTERVAL_MS = 1000;

    @DubboReference
    private PythonSandboxService pythonSandboxService;

    @Tool("Execute Python code in a secure sandbox. REQUIRED: code, dataset_id. OPTIONAL: dataset_ids (comma-separated extra dataset ids), libraries (comma-separated, e.g. 'numpy,pandas'), timeout_seconds. Dataset files are mounted under /sandbox/input/<dataset_id>/ (default: <dataset_id>.csv and <dataset_id>.meta.json). Runtime preinstalled: numpy==2.4.1, pandas==2.3.3, matplotlib==3.10.8, scipy==1.17.0. Service stack: fastapi==0.128.0, uvicorn[standard]==0.40.0, pydantic==2.12.5, llm-sandbox[docker]==0.3.33. Please prioritize using the preinstalled runtime libraries to reduce latency.")
    public String executePython(String code, String dataset_id, String dataset_ids, String libraries, Integer timeout_seconds) {
        try {
            log.info("Executing python task for dataset: {}", dataset_id);
            
            ExecuteRequest.Builder requestBuilder = ExecuteRequest.newBuilder()
                    .setCode(code)
                    .setDatasetId(dataset_id);

            for (String extraId : parseDatasetIds(dataset_ids)) {
                requestBuilder.addDatasetIds(extraId);
            }

            if (libraries != null && !libraries.isBlank()) {
                for (String lib : libraries.split(",")) {
                    requestBuilder.addLibraries(lib.trim());
                }
            }
            
            int timeout = (timeout_seconds != null && timeout_seconds > 0) ? timeout_seconds : 30;
            requestBuilder.setTimeoutSeconds(timeout);

            ExecuteResponse createResp = pythonSandboxService.createTask(requestBuilder.build());
            if (createResp.getError() != null && !createResp.getError().isEmpty()) {
                return "Failed to create task: " + createResp.getError();
            }

            String taskId = createResp.getTaskId();
            log.info("Task created: {}", taskId);

            // Poll for completion
            long maxWaitMs = timeout * 1000L + 5000; // timeout + 5s buffer
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
                    return "Task polling interrupted";
                }
            }

            // 任务可能在超时边界附近完成，再给一段额外轮询窗口，减少“假超时”。
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
                    return "Task polling interrupted";
                }
            }

            return "Task PENDING (Timeout): " + taskId + ". You may check status later.";
            
        } catch (Exception e) {
            log.error("Execute python tool error", e);
            return "Tool Execution Error: " + e.getMessage();
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
            return formatResult(result);
        }
        if ("FAILED".equals(status)) {
            return "Task FAILED: " + statusResp.getError();
        }
        if ("CANCELED".equals(status)) {
            return "Task CANCELED";
        }
        if ("NOT_FOUND".equals(status)) {
            return "Task FAILED: task not found " + taskId;
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
                .filter(s -> !s.isEmpty())
                .distinct()
                .toArray(String[]::new);
    }

    private String formatResult(TaskResultResponse result) {
        StringBuilder sb = new StringBuilder();
        if (result.getExitCode() != 0) {
            sb.append("Exit Code: ").append(result.getExitCode()).append("\n");
        }
        if (!result.getStdout().isEmpty()) {
            sb.append("STDOUT:\n").append(result.getStdout()).append("\n");
        }
        if (!result.getStderr().isEmpty()) {
            sb.append("STDERR:\n").append(result.getStderr()).append("\n");
        }
        if (!result.getDatasetDir().isEmpty()) {
             sb.append("Dataset Dir: ").append(result.getDatasetDir()).append("\n");
        }
        return sb.toString();
    }
}

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

    @DubboReference
    private PythonSandboxService pythonSandboxService;

    @Tool("Execute Python code in a secure sandbox. REQUIRED: code, dataset_id. OPTIONAL: libraries (comma-separated, e.g. 'numpy,pandas'), timeout_seconds. NOTE: Only 'numpy', 'pandas', 'matplotlib', and 'scipy' are pre-installed and supported for efficient data calculation and analysis. Please prioritize using these libraries.")
    public String executePython(String code, String dataset_id, String libraries, Integer timeout_seconds) {
        try {
            log.info("Executing python task for dataset: {}", dataset_id);
            
            ExecuteRequest.Builder requestBuilder = ExecuteRequest.newBuilder()
                    .setCode(code)
                    .setDatasetId(dataset_id);

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
                TaskStatusResponse statusResp = pythonSandboxService.getTaskStatus(
                        GetTaskStatusRequest.newBuilder().setTaskId(taskId).build()
                );
                
                String status = statusResp.getStatus();
                if ("SUCCEEDED".equals(status)) {
                    TaskResultResponse result = pythonSandboxService.getTaskResult(
                            GetTaskResultRequest.newBuilder().setTaskId(taskId).build()
                    );
                    return formatResult(result);
                } else if ("FAILED".equals(status)) {
                    return "Task FAILED: " + statusResp.getError();
                } else if ("CANCELED".equals(status)) {
                    return "Task CANCELED";
                }

                try {
                    TimeUnit.MILLISECONDS.sleep(1000);
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

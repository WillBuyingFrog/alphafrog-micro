package world.willfrog.sandbox.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.util.ArrayList;
import java.util.List;

@DubboService
@Slf4j
public class PythonSandboxGatewayServiceImpl extends DubboPythonSandboxServiceTriple.PythonSandboxServiceImplBase {

    private final RestTemplate restTemplate;

    @Value("${sandbox.service.url}")
    private String sandboxUrl;

    public PythonSandboxGatewayServiceImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public ExecuteResponse createTask(ExecuteRequest request) {
        try {
            HttpExecuteRequest httpRequest = new HttpExecuteRequest();
            httpRequest.setDataset_id(request.getDatasetId());
            httpRequest.setDataset_ids(request.getDatasetIdsList());
            httpRequest.setCode(request.getCode());
            httpRequest.setFiles(request.getFilesList());
            httpRequest.setLibraries(request.getLibrariesList());
            if (request.getTimeoutSeconds() > 0) {
                httpRequest.setTimeout_seconds(request.getTimeoutSeconds());
            }

            ResponseEntity<HttpCreateTaskResponse> response = restTemplate.postForEntity(
                    sandboxUrl + "/tasks", httpRequest, HttpCreateTaskResponse.class);

            if (response.getBody() != null) {
                return ExecuteResponse.newBuilder()
                        .setTaskId(response.getBody().getTask_id())
                        .setStatus(response.getBody().getStatus())
                        .build();
            } else {
                return ExecuteResponse.newBuilder().setError("Empty response from sandbox").build();
            }
        } catch (Exception e) {
            log.error("Failed to create task", e);
            return ExecuteResponse.newBuilder().setError(e.getMessage()).build();
        }
    }

    @Override
    public TaskStatusResponse getTaskStatus(GetTaskStatusRequest request) {
        try {
            ResponseEntity<HttpTask> response = restTemplate.getForEntity(
                    sandboxUrl + "/tasks/" + request.getTaskId(), HttpTask.class);

            if (response.getBody() != null) {
                HttpTask task = response.getBody();
                TaskStatusResponse.Builder builder = TaskStatusResponse.newBuilder()
                        .setTaskId(task.getTask_id())
                        .setStatus(task.getStatus());
                if (task.getStarted_at() != null) builder.setStartedAt(task.getStarted_at());
                if (task.getFinished_at() != null) builder.setFinishedAt(task.getFinished_at());
                if (task.getError() != null) builder.setError(task.getError());
                return builder.build();
            } else {
                return TaskStatusResponse.newBuilder().setStatus("UNKNOWN").setError("Task not found").build();
            }
        } catch (HttpClientErrorException.NotFound e) {
             return TaskStatusResponse.newBuilder().setStatus("UNKNOWN").setError("Task not found").build();
        } catch (Exception e) {
            log.error("Failed to get task status", e);
            return TaskStatusResponse.newBuilder().setStatus("UNKNOWN").setError(e.getMessage()).build();
        }
    }

    @Override
    public TaskResultResponse getTaskResult(GetTaskResultRequest request) {
        try {
            // Check status first to ensure we don't hit 409
            TaskStatusResponse status = getTaskStatus(GetTaskStatusRequest.newBuilder().setTaskId(request.getTaskId()).build());
            if ("SUCCEEDED".equals(status.getStatus())) {
                 ResponseEntity<HttpExecuteResult> response = restTemplate.getForEntity(
                    sandboxUrl + "/tasks/" + request.getTaskId() + "/result", HttpExecuteResult.class);
                 if (response.getBody() != null) {
                     HttpExecuteResult res = response.getBody();
                     return TaskResultResponse.newBuilder()
                             .setTaskId(request.getTaskId())
                             .setStatus("SUCCEEDED")
                             .setExitCode(res.getExit_code())
                             .setStdout(res.getStdout() != null ? res.getStdout() : "")
                             .setStderr(res.getStderr() != null ? res.getStderr() : "")
                             .setDatasetDir(res.getDataset_dir() != null ? res.getDataset_dir() : "")
                             .build();
                 }
            } else if ("FAILED".equals(status.getStatus())) {
                return TaskResultResponse.newBuilder()
                        .setTaskId(request.getTaskId())
                        .setStatus("FAILED")
                        .setError(status.getError())
                        .build();
            }
            
            return TaskResultResponse.newBuilder()
                    .setTaskId(request.getTaskId())
                    .setStatus(status.getStatus())
                    .setError("Result not available (Task " + status.getStatus() + ")")
                    .build();

        } catch (Exception e) {
             log.error("Failed to get task result", e);
             return TaskResultResponse.newBuilder().setError(e.getMessage()).build();
        }
    }

    // Inner DTOs for JSON mapping
    @Data
    static class HttpExecuteRequest {
        private String dataset_id;
        private List<String> dataset_ids;
        private String code;
        private List<String> files;
        private List<String> libraries;
        private Double timeout_seconds;
    }

    @Data
    static class HttpCreateTaskResponse {
        private String task_id;
        private String status;
    }

    @Data
    static class HttpTask {
        private String task_id;
        private String status;
        private String error;
        private String started_at;
        private String finished_at;
    }
    
    @Data
    static class HttpExecuteResult {
        private int exit_code;
        private String stdout;
        private String stderr;
        private String dataset_dir;
    }
}

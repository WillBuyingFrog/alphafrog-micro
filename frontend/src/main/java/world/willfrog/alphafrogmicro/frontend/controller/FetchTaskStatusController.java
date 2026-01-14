package world.willfrog.alphafrogmicro.frontend.controller;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import world.willfrog.alphafrogmicro.frontend.model.FetchTaskStatus;
import world.willfrog.alphafrogmicro.frontend.service.FetchTaskStatusService;

import java.util.Optional;

@Controller
@RequestMapping("/fetch/task")
@RequiredArgsConstructor
@Slf4j
public class FetchTaskStatusController {

    private final FetchTaskStatusService fetchTaskStatusService;

    @GetMapping("/status")
    public ResponseEntity<String> getFetchTaskStatus(@RequestParam(name = "task_uuid") String taskUuid) {
        if (taskUuid == null || taskUuid.isBlank()) {
            return ResponseEntity.badRequest().body("{\"message\":\"task_uuid is required\"}");
        }

        if (log.isDebugEnabled()) {
            log.debug("Query fetch task status task_uuid={}", taskUuid);
        }
        Optional<FetchTaskStatus> statusOptional = fetchTaskStatusService.getStatus(taskUuid);
        if (statusOptional.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("Fetch task status not found task_uuid={}", taskUuid);
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("{\"message\":\"Task not found\"}");
        }

        FetchTaskStatus status = statusOptional.get();
        if (log.isDebugEnabled()) {
            log.debug("Fetch task status found task_uuid={} status={} fetched_items={}",
                    taskUuid, status.getStatus(), status.getFetchedItemsCount());
        }
        JSONObject res = new JSONObject();
        res.put("task_uuid", status.getTaskUuid());
        res.put("task_name", status.getTaskName());
        res.put("task_sub_type", status.getTaskSubType());
        res.put("status", status.getStatus());
        res.put("fetched_items_count", status.getFetchedItemsCount());
        res.put("started_at", status.getStartedAt());
        res.put("updated_at", status.getUpdatedAt());
        res.put("message", status.getMessage());
        return ResponseEntity.ok(res.toString());
    }
}

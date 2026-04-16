package world.willfrog.alphafrogmicro.frontend.controller;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.agent.AdminFetchTask;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.service.AdminFetchTaskService;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;
import world.willfrog.alphafrogmicro.frontend.service.FetchTaskDebugService;

import java.util.*;

/**
 * admin 抓取任务专用 facade
 */
@Controller
@RequestMapping("/admin/fetch-tasks")
@RequiredArgsConstructor
@Slf4j
public class AdminFetchTaskController {

    private static final int ADMIN_USER_TYPE = 1127;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";

    private final AdminFetchTaskService adminFetchTaskService;
    private final AuthService authService;
    private final UserDao userDao;
    private final FetchTaskDebugService fetchTaskDebugService;

    @PostMapping
    public ResponseEntity<?> createTask(Authentication authentication,
                                        @RequestBody Map<String, Object> requestBody) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        if (requestBody == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Request body is required"));
        }
        String templateKey = getString(requestBody, "templateKey");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) requestBody.get("params");
        if (templateKey == null || templateKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "templateKey is required"));
        }

        String createdBy = getAdminUsername(authentication);
        try {
            AdminFetchTask task = adminFetchTaskService.createTask(templateKey, params, createdBy);
            return ResponseEntity.ok(Map.of(
                    "task", convertTaskToMap(task, false),
                    "message", "Task creation request received and is being processed."
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to create admin fetch task", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create task: " + e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> listTasks(Authentication authentication,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String templateKey,
                                       @RequestParam(required = false) String taskUuid,
                                       @RequestParam(required = false) String jobUuid,
                                       @RequestParam(required = false) String taskName,
                                       @RequestParam(required = false) Integer taskSubType,
                                       @RequestParam(required = false) String sourceKind,
                                       @RequestParam(required = false) String createdFrom,
                                       @RequestParam(required = false) String createdTo,
                                       @RequestParam(required = false, defaultValue = "1") Integer page,
                                       @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        int actualPage = page == null || page < 1 ? 1 : page;
        int actualPageSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);

        Map<String, Object> result = adminFetchTaskService.listTasks(
                status, templateKey, taskUuid, jobUuid, taskName, taskSubType, sourceKind,
                createdFrom, createdTo, actualPage, actualPageSize);

        @SuppressWarnings("unchecked")
        List<AdminFetchTask> items = (List<AdminFetchTask>) result.get("items");
        List<Map<String, Object>> taskList = items.stream()
                .map(t -> convertTaskToMap(t, false))
                .toList();

        Map<String, Object> payload = new HashMap<>();
        payload.put("items", taskList);
        payload.put("total", result.get("total"));
        payload.put("page", result.get("page"));
        payload.put("pageSize", result.get("pageSize"));
        payload.put("summary", result.get("summary"));
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/{taskUuid}")
    public ResponseEntity<?> getTaskDetail(Authentication authentication,
                                           @PathVariable String taskUuid) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        if (taskUuid == null || taskUuid.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "taskUuid is required"));
        }
        AdminFetchTask task = adminFetchTaskService.getTaskDetail(taskUuid);
        if (task == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Task not found"));
        }
        Map<String, Object> map = convertTaskToMap(task, true);
        String debugBase64 = fetchTaskDebugService.getDebugRequestsBase64(taskUuid);
        if (debugBase64 != null) {
            map.put("debugRequestsBase64", debugBase64);
        }
        return ResponseEntity.ok(map);
    }

    @PostMapping(":retry")
    public ResponseEntity<?> retryTasks(Authentication authentication,
                                        @RequestBody Map<String, Object> requestBody) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        if (requestBody == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Request body is required"));
        }
        @SuppressWarnings("unchecked")
        List<String> taskUuids = (List<String>) requestBody.get("taskUuids");
        if (taskUuids == null || taskUuids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "taskUuids is required"));
        }
        List<Map<String, Object>> results = adminFetchTaskService.retryTasks(taskUuids);
        return ResponseEntity.ok(Map.of("results", results));
    }

    // ==================== 私有方法 ====================

    private Map<String, Object> convertTaskToMap(AdminFetchTask task, boolean includeDetail) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskUuid", task.getTaskUuid());
        map.put("templateKey", task.getTemplateKey());
        map.put("taskName", task.getTaskName());
        map.put("taskSubType", task.getTaskSubType());
        map.put("status", task.getStatus());
        map.put("fetchedItemsCount", task.getFetchedItemsCount());
        map.put("message", task.getMessage());
        map.put("paramsSummary", task.getParamsSummary());
        map.put("createdBy", task.getCreatedBy());
        map.put("createdAt", formatOffsetDateTime(task.getCreatedAt()));
        map.put("updatedAt", formatOffsetDateTime(task.getUpdatedAt()));
        map.put("finishedAt", formatOffsetDateTime(task.getFinishedAt()));
        map.put("retryOfTaskUuid", task.getRetryOfTaskUuid());
        map.put("jobUuid", task.getJobUuid());
        map.put("sourceKind", task.getSourceKind());
        map.put("sourceIndex", task.getSourceIndex());
        map.put("taskSetMode", task.getTaskSetMode());

        if (includeDetail) {
            map.put("inputParams", parseJson(task.getInputParams()));
            map.put("dispatchPayload", parseJson(task.getDispatchPayload()));
        }
        return map;
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSONObject.parseObject(json);
        } catch (Exception e) {
            return json;
        }
    }

    private String formatOffsetDateTime(java.time.OffsetDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.toString();
    }

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        String username = authentication.getName();
        List<User> users = userDao.getUserByUsername(username);
        if (users.isEmpty()) {
            return false;
        }
        User user = users.get(0);
        Integer userType = user.getUserType();
        if (userType == null || userType != ADMIN_USER_TYPE) {
            return false;
        }
        String status = user.getStatus();
        return status == null || status.isBlank() || STATUS_ACTIVE.equalsIgnoreCase(status);
    }

    private String getAdminUsername(Authentication authentication) {
        if (authentication == null) {
            return "";
        }
        return authentication.getName();
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }
}

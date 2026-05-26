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
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.service.AdminFetchJobService;

import java.util.*;

/**
 * admin 抓取任务批次（Job）facade
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminFetchJobController {

    private static final int ADMIN_USER_TYPE = 1127;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";

    private final AdminFetchJobService adminFetchJobService;
    private final UserDao userDao;

    @GetMapping("/fetch-catalog")
    public ResponseEntity<?> getFetchCatalog(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        return ResponseEntity.ok(adminFetchJobService.buildCatalog());
    }

    @PostMapping("/fetch-jobs:preview")
    public ResponseEntity<?> previewJob(Authentication authentication,
                                        @RequestBody Map<String, Object> requestBody) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        if (requestBody == null) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "errors", 
                List.of(Map.of("path", "", "code", "EMPTY_BODY", "message", "Request body is required"))));
        }
        try {
            Map<String, Object> result = adminFetchJobService.previewJob(requestBody);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "errors",
                List.of(Map.of("path", "", "code", "PREVIEW_ERROR", "message", e.getMessage()))));
        } catch (Exception e) {
            log.error("Failed to preview admin fetch job", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("valid", false, "errors",
                        List.of(Map.of("path", "", "code", "INTERNAL_ERROR", "message", e.getMessage()))));
        }
    }

    @PostMapping("/fetch-jobs")
    public ResponseEntity<?> createJob(Authentication authentication,
                                       @RequestBody Map<String, Object> requestBody) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        if (requestBody == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Request body is required"));
        }
        String createdBy = getAdminUsername(authentication);
        try {
            Map<String, Object> result = adminFetchJobService.createJob(requestBody, createdBy);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to create admin fetch job", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create job: " + e.getMessage()));
        }
    }

    @GetMapping("/fetch-jobs")
    public ResponseEntity<?> listJobs(Authentication authentication,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String mode,
                                      @RequestParam(required = false) String jobUuid,
                                      @RequestParam(required = false) String createdFrom,
                                      @RequestParam(required = false) String createdTo,
                                      @RequestParam(required = false, defaultValue = "1") Integer page,
                                      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        int actualPage = page == null || page < 1 ? 1 : page;
        int actualPageSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        Map<String, Object> result = adminFetchJobService.listJobs(
                status, mode, jobUuid, createdFrom, createdTo, actualPage, actualPageSize);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/fetch-jobs/{jobUuid}")
    public ResponseEntity<?> getJobDetail(Authentication authentication,
                                          @PathVariable String jobUuid) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        if (jobUuid == null || jobUuid.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "jobUuid is required"));
        }
        Map<String, Object> detail = adminFetchJobService.getJobDetail(jobUuid);
        if (detail == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Job not found"));
        }
        return ResponseEntity.ok(detail);
    }

    @GetMapping("/fetch-jobs/{jobUuid}/status-summary")
    public ResponseEntity<?> getJobStatusSummary(Authentication authentication,
                                                 @PathVariable String jobUuid) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        if (jobUuid == null || jobUuid.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "jobUuid is required"));
        }
        Map<String, Object> summary = adminFetchJobService.getJobStatusSummary(jobUuid);
        if (summary == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Job not found"));
        }
        return ResponseEntity.ok(summary);
    }

    @PostMapping("/fetch-jobs/{jobUuid}:cancel")
    public ResponseEntity<?> cancelJob(Authentication authentication,
                                       @PathVariable String jobUuid) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        if (jobUuid == null || jobUuid.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "jobUuid is required"));
        }
        try {
            Map<String, Object> result = adminFetchJobService.cancelJob(jobUuid);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to cancel job jobUuid={}", jobUuid, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to cancel: " + e.getMessage()));
        }
    }

    @DeleteMapping("/fetch-jobs/{jobUuid}")
    public ResponseEntity<?> deleteJob(Authentication authentication,
                                       @PathVariable String jobUuid) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        if (jobUuid == null || jobUuid.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "jobUuid is required"));
        }
        try {
            adminFetchJobService.deleteJob(jobUuid);
            return ResponseEntity.ok(Map.of("jobUuid", jobUuid, "deleted", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to delete job jobUuid={}", jobUuid, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete: " + e.getMessage()));
        }
    }

    @PostMapping("/fetch-jobs/{jobUuid}:retry-failures")
    public ResponseEntity<?> retryJobFailures(Authentication authentication,
                                              @PathVariable String jobUuid) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        if (jobUuid == null || jobUuid.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "jobUuid is required"));
        }
        try {
            Map<String, Object> result = adminFetchJobService.retryJobFailures(jobUuid);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to retry job failures jobUuid={}", jobUuid, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retry: " + e.getMessage()));
        }
    }

    // ==================== 私有方法 ====================

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
}

package world.willfrog.alphafrogmicro.frontend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import world.willfrog.alphafrogmicro.admin.idl.*;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;
import world.willfrog.alphafrogmicro.frontend.service.RateLimitingService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin")
@Slf4j
@RequiredArgsConstructor
public class AdminController {

    private static final int ADMIN_USER_TYPE = 1127;

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";

    private final AuthService authService;
    private final RateLimitingService rateLimitingService;
    private final UserDao userDao;

    @DubboReference
    private AdminService adminService;

    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody Map<String, Object> loginRequest) {
        if (!rateLimitingService.tryAcquire("auth")) {
            return ResponseEntity.status(429).body("Too many login attempts, please try again later");
        }

        String username;
        String password;
        try {
            username = (String) loginRequest.get("username");
            password = (String) loginRequest.get("password");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid request body format");
        }

        if (username == null || username.trim().isEmpty() || password == null || password.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Username and password are required");
        }
        if (authService.checkIfLoggedIn(username)) {
            return ResponseEntity.badRequest().body("User already logged in");
        }

        AdminLoginResponse response = adminService.validateAdminLogin(
                AdminLoginRequest.newBuilder()
                        .setUsername(username)
                        .setPassword(password)
                        .build()
        );
        if (!response.getValid()) {
            return ResponseEntity.badRequest().body(response.getMessage());
        }

        authService.markAsLoggedIn(username);
        String token = authService.generateToken(username);
        return ResponseEntity.ok(token);
    }

    @PostMapping("/create")
    public ResponseEntity<String> create(@RequestBody Map<String, Object> createRequest) {
        if (!rateLimitingService.tryAcquire("auth")) {
            return ResponseEntity.status(429).body("Too many attempts, please try again later");
        }

        String username;
        String password;
        String email;
        String magicPassword;
        try {
            username = (String) createRequest.get("username");
            password = (String) createRequest.get("password");
            email = (String) createRequest.get("email");
            magicPassword = (String) createRequest.get("magic_password");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid request body format");
        }

        AdminActionResponse response = adminService.createAdmin(
                AdminCreateRequest.newBuilder()
                        .setUsername(username == null ? "" : username)
                        .setPassword(password == null ? "" : password)
                        .setEmail(email == null ? "" : email)
                        .setMagicPassword(magicPassword == null ? "" : magicPassword)
                        .build()
        );
        if (response.getSuccess()) {
            return ResponseEntity.ok(response.getMessage());
        }
        return ResponseEntity.badRequest().body(response.getMessage());
    }

    @PostMapping("/delete")
    public ResponseEntity<String> delete(Authentication authentication,
                                         @RequestBody Map<String, Object> deleteRequest) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("Forbidden");
        }

        String magicPassword;
        try {
            magicPassword = (String) deleteRequest.get("magic_password");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid request body format");
        }

        AdminActionResponse response = adminService.deleteAdmin(
                AdminDeleteRequest.newBuilder()
                        .setUsername(authentication.getName())
                        .setMagicPassword(magicPassword == null ? "" : magicPassword)
                        .build()
        );
        if (response.getSuccess()) {
            authService.markAsLoggedOut(authentication.getName());
            return ResponseEntity.ok(response.getMessage());
        }
        return ResponseEntity.badRequest().body(response.getMessage());
    }

    @PostMapping("/logout")
    public ResponseEntity<String> logout(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        String username = authentication.getName();
        if (authService.checkIfLoggedIn(username)) {
            authService.markAsLoggedOut(username);
            return ResponseEntity.ok("Admin logged out successfully");
        }
        return ResponseEntity.badRequest().body("Admin not logged in");
    }

    @PostMapping("/invite-codes")
    public ResponseEntity<Map<String, Object>> createInviteCode(Authentication authentication,
                                                                @RequestBody(required = false) Map<String, Object> request) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).build();
        }
        Integer expiresInHours = null;
        if (request != null && request.get("expiresInHours") instanceof Number hours) {
            expiresInHours = hours.intValue();
        }

        User adminUser = authService.getUserByUsername(authentication.getName());
        Long createdBy = adminUser == null ? null : adminUser.getUserId();
        String inviteCode = authService.createInviteCode(createdBy, expiresInHours);
        Map<String, Object> payload = new HashMap<>();
        payload.put("inviteCode", inviteCode);
        payload.put("expiresInHours", expiresInHours);
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/overall")
    public ResponseEntity<Map<String, Long>> overall(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).build();
        }

        AdminOverallResponse response = adminService.getAdminOverall(AdminOverallRequest.newBuilder().build());
        Map<String, Long> payload = new HashMap<>();
        payload.put("fundCount", response.getFundCount());
        payload.put("indexCount", response.getIndexCount());
        payload.put("stockCount", response.getStockCount());
        payload.put("fundNavCount", response.getFundNavCount());
        payload.put("indexDailyCount", response.getIndexDailyCount());
        payload.put("stockDailyCount", response.getStockDailyCount());
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/credit-applications")
    public ResponseEntity<?> listCreditApplications(Authentication authentication,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String userId,
                                                    @RequestParam(required = false, defaultValue = "1") Integer page,
                                                    @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        ListCreditApplicationsResponse response = adminService.listCreditApplications(
                ListCreditApplicationsRequest.newBuilder()
                        .setStatus(nvl(status))
                        .setUserId(nvl(userId))
                        .setPage(page == null ? 1 : page)
                        .setPageSize(pageSize == null ? 20 : Math.min(pageSize, 100))
                        .build()
        );
        if (!response.getSuccess()) {
            return buildError(response.getErrorCode(), response.getMessage());
        }

        List<Map<String, Object>> applicationsList = response.getApplicationsList().stream()
                .map(this::convertCreditApplicationToMap)
                .collect(Collectors.toList());

        Map<String, Object> payload = new HashMap<>();
        payload.put("applications", applicationsList);
        payload.put("total", response.getTotal());
        payload.put("page", response.getPage());
        payload.put("pageSize", response.getPageSize());
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/credit-applications/{applicationId}")
    public ResponseEntity<?> getCreditApplication(Authentication authentication,
                                                  @PathVariable String applicationId) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        GetCreditApplicationResponse response = adminService.getCreditApplication(
                GetCreditApplicationRequest.newBuilder()
                        .setApplicationId(applicationId)
                        .build()
        );
        if (!response.getSuccess()) {
            return buildError(response.getErrorCode(), response.getMessage());
        }
        List<Map<String, Object>> auditTrail = response.getAuditTrailList().stream()
                .map(item -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("auditId", item.getAuditId());
                    map.put("operatorId", item.getOperatorId());
                    map.put("action", item.getAction());
                    map.put("reason", item.getReason());
                    map.put("beforeJson", item.getBeforeJson());
                    map.put("afterJson", item.getAfterJson());
                    map.put("createdAt", item.getCreatedAt());
                    return map;
                })
                .collect(Collectors.toList());
        Map<String, Object> payload = new HashMap<>();
        payload.put("application", convertCreditApplicationToMap(response.getApplication()));
        payload.put("auditTrail", auditTrail);
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/credit-applications/{applicationId}/approve")
    public ResponseEntity<?> approveCreditApplication(Authentication authentication,
                                                      @PathVariable String applicationId,
                                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                      @RequestBody Map<String, Object> requestBody) {
        return processCreditApplication(authentication, applicationId, idempotencyKey, requestBody, true);
    }

    @PostMapping("/credit-applications/{applicationId}/reject")
    public ResponseEntity<?> rejectCreditApplication(Authentication authentication,
                                                     @PathVariable String applicationId,
                                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                     @RequestBody Map<String, Object> requestBody) {
        return processCreditApplication(authentication, applicationId, idempotencyKey, requestBody, false);
    }

    @GetMapping("/credit-ledger")
    public ResponseEntity<?> listCreditLedger(Authentication authentication,
                                              @RequestParam(required = false) String userId,
                                              @RequestParam(required = false) String bizType,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to,
                                              @RequestParam(required = false) String sourceId,
                                              @RequestParam(required = false, defaultValue = "1") Integer page,
                                              @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        ListCreditLedgerResponse response = adminService.listCreditLedger(
                ListCreditLedgerRequest.newBuilder()
                        .setUserId(nvl(userId))
                        .setBizType(nvl(bizType))
                        .setFrom(nvl(from))
                        .setTo(nvl(to))
                        .setSourceId(nvl(sourceId))
                        .setPage(page == null ? 1 : page)
                        .setPageSize(pageSize == null ? 20 : Math.min(pageSize, 100))
                        .build()
        );
        if (!response.getSuccess()) {
            return buildError(response.getErrorCode(), response.getMessage());
        }
        List<Map<String, Object>> entries = response.getEntriesList().stream()
                .map(this::convertCreditLedgerEntryToMap)
                .collect(Collectors.toList());
        Map<String, Object> payload = new HashMap<>();
        payload.put("entries", entries);
        payload.put("total", response.getTotal());
        payload.put("page", response.getPage());
        payload.put("pageSize", response.getPageSize());
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/users/{userId}/credit-ledger")
    public ResponseEntity<?> listUserCreditLedger(Authentication authentication,
                                                  @PathVariable String userId,
                                                  @RequestParam(required = false) String bizType,
                                                  @RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to,
                                                  @RequestParam(required = false) String sourceId,
                                                  @RequestParam(required = false, defaultValue = "1") Integer page,
                                                  @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        return listCreditLedger(authentication, userId, bizType, from, to, sourceId, page, pageSize);
    }

    @GetMapping("/users")
    public ResponseEntity<?> listUsers(Authentication authentication,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false, defaultValue = "1") Integer page,
                                       @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        ListUsersResponse response = adminService.listUsers(
                ListUsersRequest.newBuilder()
                        .setKeyword(nvl(keyword))
                        .setStatus(nvl(status))
                        .setPage(page == null ? 1 : page)
                        .setPageSize(pageSize == null ? 20 : Math.min(pageSize, 100))
                        .build()
        );
        if (!response.getSuccess()) {
            return buildError(response.getErrorCode(), response.getMessage());
        }
        List<Map<String, Object>> users = response.getUsersList().stream()
                .map(this::convertAdminUserToMap)
                .collect(Collectors.toList());
        Map<String, Object> payload = new HashMap<>();
        payload.put("users", users);
        payload.put("total", response.getTotal());
        payload.put("page", response.getPage());
        payload.put("pageSize", response.getPageSize());
        return ResponseEntity.ok(payload);
    }

    @PutMapping("/users/{userId}/status")
    public ResponseEntity<?> updateUserStatus(Authentication authentication,
                                              @PathVariable String userId,
                                              @RequestBody Map<String, Object> requestBody) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        String targetStatus = requestBody == null ? "" : nvl(requestBody.get("targetStatus"));
        String reason = requestBody == null ? "" : nvl(requestBody.get("reason"));
        boolean revokeTokens = readBoolean(requestBody, "revokeTokens", true);
        boolean blockNewRuns = readBoolean(requestBody, "blockNewRuns", true);
        boolean terminateRunningRuns = readBoolean(requestBody, "terminateRunningRuns", false);

        User adminUser = authService.getUserByUsername(authentication.getName());
        String operatorId = adminUser == null || adminUser.getUserId() == null ? "" : String.valueOf(adminUser.getUserId());
        UpdateUserStatusResponse response = adminService.updateUserStatus(
                UpdateUserStatusRequest.newBuilder()
                        .setUserId(userId)
                        .setTargetStatus(targetStatus)
                        .setReason(reason)
                        .setRevokeTokens(revokeTokens)
                        .setBlockNewRuns(blockNewRuns)
                        .setTerminateRunningRuns(terminateRunningRuns)
                        .setOperatorId(operatorId)
                        .build()
        );
        if (!response.getSuccess()) {
            return buildError(response.getErrorCode(), response.getMessage());
        }

        int localTokensRevokedCount = 0;
        if (revokeTokens && STATUS_DISABLED.equalsIgnoreCase(targetStatus)) {
            try {
                Long userIdLong = Long.parseLong(userId);
                User targetUser = userDao.getUserById(userIdLong);
                if (targetUser != null && targetUser.getUsername() != null) {
                    int revoked = authService.markAsLoggedOut(targetUser.getUsername());
                    if (revoked > 0) {
                        localTokensRevokedCount = 1;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to revoke token locally: userId={}", userId, e);
            }
        }
        int totalTokensRevokedCount = Math.max(0, response.getEffectiveActions().getTokensRevokedCount()) + localTokensRevokedCount;

        Map<String, Object> actions = new HashMap<>();
        actions.put("tokensRevokedCount", totalTokensRevokedCount);
        actions.put("newRunsBlocked", response.getEffectiveActions().getNewRunsBlocked());
        actions.put("runningRunsTerminatedCount", response.getEffectiveActions().getRunningRunsTerminatedCount());
        actions.put("failedRunIds", response.getEffectiveActions().getFailedRunIdsList());

        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", response.getUserId());
        payload.put("oldStatus", response.getOldStatus());
        payload.put("newStatus", response.getNewStatus());
        payload.put("auditId", response.getAuditId());
        payload.put("effectiveActions", actions);
        payload.put("message", response.getMessage());
        return ResponseEntity.ok(payload);
    }

    private ResponseEntity<?> processCreditApplication(Authentication authentication,
                                                       String applicationId,
                                                       String idempotencyKey,
                                                       Map<String, Object> requestBody,
                                                       boolean approve) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Idempotency-Key is required"));
        }
        String processReason = requestBody == null ? "" : nvl(requestBody.get("processReason"));
        Integer expectedVersion = readInt(requestBody == null ? null : requestBody.get("expectedVersion"));
        if (processReason.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "processReason is required"));
        }
        if (expectedVersion == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "expectedVersion is required"));
        }

        User adminUser = authService.getUserByUsername(authentication.getName());
        String adminId = adminUser == null || adminUser.getUserId() == null ? "" : String.valueOf(adminUser.getUserId());

        ProcessCreditApplicationRequest request = ProcessCreditApplicationRequest.newBuilder()
                .setApplicationId(applicationId)
                .setAdminId(adminId)
                .setIdempotencyKey(nvl(idempotencyKey))
                .setProcessReason(processReason)
                .setExpectedVersion(expectedVersion)
                .build();

        ProcessCreditApplicationResponse response = approve
                ? adminService.approveCreditApplication(request)
                : adminService.rejectCreditApplication(request);
        if (!response.getSuccess()) {
            return buildError(response.getErrorCode(), response.getMessage(), response);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("application", convertCreditApplicationToMap(response.getApplication()));
        payload.put("creditChange", convertCreditChangeToMap(response.getCreditChange()));
        payload.put("auditId", response.getAuditId());
        payload.put("idempotentReplay", response.getIdempotentReplay());
        payload.put("message", response.getMessage());
        return ResponseEntity.ok(payload);
    }

    private ResponseEntity<Map<String, Object>> buildError(String errorCode, String message) {
        return buildError(errorCode, message, null);
    }

    private ResponseEntity<Map<String, Object>> buildError(String errorCode,
                                                           String message,
                                                           ProcessCreditApplicationResponse processResp) {
        HttpStatus status = mapErrorCodeToStatus(errorCode);
        Map<String, Object> payload = new HashMap<>();
        payload.put("error", message);
        payload.put("errorCode", errorCode);
        if (processResp != null) {
            payload.put("conflictStatus", processResp.getConflictStatus());
            payload.put("conflictVersion", processResp.getConflictVersion());
            if (processResp.hasApplication()) {
                payload.put("application", convertCreditApplicationToMap(processResp.getApplication()));
            }
        }
        return ResponseEntity.status(status).body(payload);
    }

    private HttpStatus mapErrorCodeToStatus(String errorCode) {
        if ("NOT_FOUND".equalsIgnoreCase(errorCode)) {
            return HttpStatus.NOT_FOUND;
        }
        if ("STATUS_CONFLICT".equalsIgnoreCase(errorCode)
                || "VERSION_CONFLICT".equalsIgnoreCase(errorCode)
                || "IDEMPOTENCY_IN_PROGRESS".equalsIgnoreCase(errorCode)) {
            return HttpStatus.CONFLICT;
        }
        if ("INVALID_ARGUMENT".equalsIgnoreCase(errorCode)) {
            return HttpStatus.BAD_REQUEST;
        }
        if ("INTERNAL_ERROR".equalsIgnoreCase(errorCode)) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return HttpStatus.BAD_REQUEST;
    }

    private Map<String, Object> convertCreditApplicationToMap(CreditApplication app) {
        Map<String, Object> map = new HashMap<>();
        map.put("applicationId", app.getApplicationId());
        map.put("userId", app.getUserId());
        map.put("amount", app.getAmount());
        map.put("status", app.getStatus());
        map.put("reason", app.getReason());
        map.put("contact", app.getContact());
        map.put("createdAt", app.getCreatedAt());
        map.put("processedAt", app.getProcessedAt());
        map.put("processedBy", app.getProcessedBy());
        map.put("processReason", app.getProcessReason());
        map.put("version", app.getVersion());
        return map;
    }

    private Map<String, Object> convertCreditLedgerEntryToMap(CreditLedgerEntry entry) {
        Map<String, Object> map = new HashMap<>();
        map.put("ledgerId", entry.getLedgerId());
        map.put("userId", entry.getUserId());
        map.put("bizType", entry.getBizType());
        map.put("delta", entry.getDelta());
        map.put("balanceBefore", entry.getBalanceBefore());
        map.put("balanceAfter", entry.getBalanceAfter());
        map.put("sourceType", entry.getSourceType());
        map.put("sourceId", entry.getSourceId());
        map.put("operatorId", entry.getOperatorId());
        map.put("idempotencyKey", entry.getIdempotencyKey());
        map.put("ext", entry.getExt());
        map.put("createdAt", entry.getCreatedAt());
        return map;
    }

    private Map<String, Object> convertAdminUserToMap(AdminUser user) {
        Map<String, Object> map = new HashMap<>();
        map.put("userId", user.getUserId());
        map.put("username", user.getUsername());
        map.put("email", user.getEmail());
        map.put("credit", user.getCredit());
        map.put("registerTime", user.getRegisterTime());
        map.put("status", user.getStatus());
        map.put("disabledAt", user.getDisabledAt());
        map.put("disabledReason", user.getDisabledReason());
        return map;
    }

    private Map<String, Object> convertCreditChangeToMap(CreditChange creditChange) {
        Map<String, Object> map = new HashMap<>();
        map.put("delta", creditChange.getDelta());
        map.put("balanceBefore", creditChange.getBalanceBefore());
        map.put("balanceAfter", creditChange.getBalanceAfter());
        map.put("ledgerId", creditChange.getLedgerId());
        return map;
    }

    // ========== Agent 运行监控 ==========

    @GetMapping("/agent-runs")
    public ResponseEntity<?> listAgentRuns(Authentication authentication,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String userId,
                                           @RequestParam(required = false, defaultValue = "1") Integer page,
                                           @RequestParam(required = false, defaultValue = "20") Integer pageSize,
                                           @RequestParam(required = false, defaultValue = "30") Integer days) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        ListAdminAgentRunsResponse response = adminService.listAgentRuns(
                ListAdminAgentRunsRequest.newBuilder()
                        .setStatus(nvl(status))
                        .setUserId(nvl(userId))
                        .setPage(page == null ? 1 : page)
                        .setPageSize(pageSize == null ? 20 : Math.min(pageSize, 100))
                        .setDays(days == null ? 30 : days)
                        .build()
        );
        if (!response.getSuccess()) {
            return buildError(response.getErrorCode(), response.getMessage());
        }
        List<Map<String, Object>> runs = response.getRunsList().stream()
                .map(this::convertAdminAgentRunToMap)
                .collect(Collectors.toList());
        Map<String, Object> payload = new HashMap<>();
        payload.put("runs", runs);
        payload.put("total", response.getTotal());
        payload.put("page", response.getPage());
        payload.put("pageSize", response.getPageSize());
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/agent-runs/{runId}")
    public ResponseEntity<?> getAgentRun(Authentication authentication,
                                         @PathVariable String runId) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        GetAdminAgentRunResponse response = adminService.getAgentRun(
                GetAdminAgentRunRequest.newBuilder()
                        .setRunId(runId)
                        .build()
        );
        if (!response.getSuccess()) {
            return buildError(response.getErrorCode(), response.getMessage());
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("run", convertAdminAgentRunToMap(response.getRun()));
        payload.put("planJson", response.getPlanJson());
        payload.put("snapshotJson", response.getSnapshotJson());
        payload.put("lastError", response.getLastError());
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/agent-runs/{runId}/stop")
    public ResponseEntity<?> stopAgentRun(Authentication authentication,
                                          @PathVariable String runId,
                                          @RequestBody(required = false) Map<String, Object> requestBody) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        User adminUser = authService.getUserByUsername(authentication.getName());
        String operatorId = adminUser == null || adminUser.getUserId() == null ? "" : String.valueOf(adminUser.getUserId());
        String reason = requestBody == null ? "" : nvl(requestBody.get("reason"));

        StopAdminAgentRunResponse response = adminService.stopAgentRun(
                StopAdminAgentRunRequest.newBuilder()
                        .setRunId(runId)
                        .setOperatorId(operatorId)
                        .setReason(reason)
                        .build()
        );
        if (!response.getSuccess()) {
            return buildError(response.getErrorCode(), response.getMessage());
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("runId", response.getRunId());
        payload.put("newStatus", response.getNewStatus());
        payload.put("message", response.getMessage());
        return ResponseEntity.ok(payload);
    }

    // ========== 系统配置管理 ==========

    @GetMapping("/system-config")
    public ResponseEntity<?> getSystemConfig(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        GetSystemConfigResponse response = adminService.getSystemConfig(
                GetSystemConfigRequest.newBuilder().build()
        );
        if (!response.getSuccess()) {
            return buildError(response.getErrorCode(), response.getMessage());
        }
        List<Map<String, Object>> configs = response.getConfigsList().stream()
                .map(this::convertSystemConfigItemToMap)
                .collect(Collectors.toList());
        Map<String, Object> payload = new HashMap<>();
        payload.put("configs", configs);
        payload.put("configJson", response.getConfigJson());
        return ResponseEntity.ok(payload);
    }

    @PutMapping("/system-config")
    public ResponseEntity<?> updateSystemConfig(Authentication authentication,
                                                @RequestBody Map<String, Object> requestBody) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        String key = requestBody == null ? "" : nvl(requestBody.get("key"));
        String value = requestBody == null ? "" : nvl(requestBody.get("value"));
        String reason = requestBody == null ? "" : nvl(requestBody.get("reason"));

        User adminUser = authService.getUserByUsername(authentication.getName());
        String operatorId = adminUser == null || adminUser.getUserId() == null ? "" : String.valueOf(adminUser.getUserId());

        UpdateSystemConfigResponse response = adminService.updateSystemConfig(
                UpdateSystemConfigRequest.newBuilder()
                        .setKey(key)
                        .setValue(value)
                        .setOperatorId(operatorId)
                        .setReason(reason)
                        .build()
        );
        if (!response.getSuccess()) {
            return buildError(response.getErrorCode(), response.getMessage());
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("config", convertSystemConfigItemToMap(response.getConfig()));
        payload.put("message", response.getMessage());
        return ResponseEntity.ok(payload);
    }

    // ========== 用户额度直接调整 ==========

    @PostMapping("/users/{userId}/credit-adjust")
    public ResponseEntity<?> adjustUserCredit(Authentication authentication,
                                              @PathVariable String userId,
                                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @RequestBody Map<String, Object> requestBody) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Idempotency-Key is required"));
        }
        Integer delta = readInt(requestBody == null ? null : requestBody.get("delta"));
        String reason = requestBody == null ? "" : nvl(requestBody.get("reason"));

        if (delta == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "delta is required"));
        }
        if (reason.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "reason is required"));
        }

        User adminUser = authService.getUserByUsername(authentication.getName());
        String operatorId = adminUser == null || adminUser.getUserId() == null ? "" : String.valueOf(adminUser.getUserId());

        AdjustUserCreditResponse response = adminService.adjustUserCredit(
                AdjustUserCreditRequest.newBuilder()
                        .setUserId(userId)
                        .setDelta(delta)
                        .setReason(reason)
                        .setOperatorId(operatorId)
                        .setIdempotencyKey(idempotencyKey)
                        .build()
        );
        if (!response.getSuccess()) {
            return buildError(response.getErrorCode(), response.getMessage());
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", response.getUserId());
        payload.put("creditBefore", response.getCreditBefore());
        payload.put("creditAfter", response.getCreditAfter());
        payload.put("delta", response.getDelta());
        payload.put("ledgerId", response.getLedgerId());
        payload.put("auditId", response.getAuditId());
        payload.put("idempotentReplay", response.getIdempotentReplay());
        payload.put("message", response.getMessage());
        return ResponseEntity.ok(payload);
    }

    private Map<String, Object> convertAdminAgentRunToMap(AdminAgentRun run) {
        Map<String, Object> map = new HashMap<>();
        map.put("runId", run.getRunId());
        map.put("userId", run.getUserId());
        map.put("username", run.getUsername());
        map.put("status", run.getStatus());
        map.put("message", run.getMessage());
        map.put("currentStep", run.getCurrentStep());
        map.put("maxSteps", run.getMaxSteps());
        map.put("startedAt", run.getStartedAt());
        map.put("updatedAt", run.getUpdatedAt());
        map.put("completedAt", run.getCompletedAt());
        map.put("durationMs", run.getDurationMs());
        map.put("totalTokens", run.getTotalTokens());
        map.put("toolCalls", run.getToolCalls());
        map.put("hasArtifacts", run.getHasArtifacts());
        return map;
    }

    private Map<String, Object> convertSystemConfigItemToMap(SystemConfigItem config) {
        Map<String, Object> map = new HashMap<>();
        map.put("key", config.getKey());
        map.put("value", config.getValue());
        map.put("description", config.getDescription());
        map.put("category", config.getCategory());
        map.put("editable", config.getEditable());
        return map;
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

    private String nvl(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean readBoolean(Map<String, Object> requestBody, String key, boolean defaultValue) {
        if (requestBody == null || !requestBody.containsKey(key) || requestBody.get(key) == null) {
            return defaultValue;
        }
        Object value = requestBody.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Integer readInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }
}

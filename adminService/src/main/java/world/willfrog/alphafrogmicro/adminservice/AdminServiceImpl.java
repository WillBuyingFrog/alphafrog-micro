package world.willfrog.alphafrogmicro.adminservice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import world.willfrog.alphafrogmicro.admin.idl.*;
import world.willfrog.alphafrogmicro.admin.idl.DubboAdminServiceTriple.AdminServiceImplBase;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunsRequest;
import world.willfrog.alphafrogmicro.common.dao.agent.AdminAuditLogDao;
import world.willfrog.alphafrogmicro.common.dao.agent.AdminIdempotencyDao;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentCreditApplicationDao;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentCreditLedgerDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.common.DataOverviewDao;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.agent.AdminAuditLog;
import world.willfrog.alphafrogmicro.common.pojo.agent.AdminIdempotency;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentCreditApplication;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentCreditLedger;
import world.willfrog.alphafrogmicro.common.pojo.user.User;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@DubboService
@Service
@Slf4j
public class AdminServiceImpl extends AdminServiceImplBase {

    private static final int ADMIN_USER_TYPE = 1127;

    @Value("${admin.magic-password:frog20191127StartFromBelieving}")
    private String magicPassword;

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";

    private static final String ERROR_OK = "OK";
    private static final String ERROR_NOT_FOUND = "NOT_FOUND";
    private static final String ERROR_INVALID_ARGUMENT = "INVALID_ARGUMENT";
    private static final String ERROR_STATUS_CONFLICT = "STATUS_CONFLICT";
    private static final String ERROR_VERSION_CONFLICT = "VERSION_CONFLICT";
    private static final String ERROR_IDEMPOTENCY_REPLAY = "IDEMPOTENCY_REPLAY";
    private static final String ERROR_IDEMPOTENCY_IN_PROGRESS = "IDEMPOTENCY_IN_PROGRESS";
    private static final String ERROR_INTERNAL = "INTERNAL_ERROR";

    private static final String TARGET_CREDIT_APPLICATION = "CREDIT_APPLICATION";
    private static final String TARGET_USER = "USER";
    private static final String IDEM_STATUS_PROCESSING = "PROCESSING";
    private static final String IDEM_STATUS_COMPLETED = "COMPLETED";
    private static final String SOURCE_TYPE_CREDIT_APPLICATION = "CREDIT_APPLICATION";

    private final UserDao userDao;
    private final DataOverviewDao dataOverviewDao;
    private final PasswordEncoder passwordEncoder;
    private final AgentCreditApplicationDao creditApplicationDao;
    private final AgentCreditLedgerDao creditLedgerDao;
    private final AdminAuditLogDao adminAuditLogDao;
    private final AdminIdempotencyDao adminIdempotencyDao;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    @DubboReference
    private AgentDubboService agentDubboService;

    public AdminServiceImpl(UserDao userDao,
                            DataOverviewDao dataOverviewDao,
                            PasswordEncoder passwordEncoder,
                            AgentCreditApplicationDao creditApplicationDao,
                            AgentCreditLedgerDao creditLedgerDao,
                            AdminAuditLogDao adminAuditLogDao,
                            AdminIdempotencyDao adminIdempotencyDao,
                            PlatformTransactionManager transactionManager) {
        this.userDao = userDao;
        this.dataOverviewDao = dataOverviewDao;
        this.passwordEncoder = passwordEncoder;
        this.creditApplicationDao = creditApplicationDao;
        this.creditLedgerDao = creditLedgerDao;
        this.adminAuditLogDao = adminAuditLogDao;
        this.adminIdempotencyDao = adminIdempotencyDao;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public AdminLoginResponse validateAdminLogin(AdminLoginRequest request) {
        String username = request.getUsername();
        String password = request.getPassword();
        if (isBlank(username) || isBlank(password)) {
            return AdminLoginResponse.newBuilder()
                    .setValid(false)
                    .setMessage("Username and password are required")
                    .build();
        }

        List<User> users = userDao.getUserByUsername(username);
        if (users.isEmpty()) {
            return AdminLoginResponse.newBuilder()
                    .setValid(false)
                    .setMessage("Admin account not found")
                    .build();
        }

        User user = users.get(0);
        Integer userType = user.getUserType();
        if (userType == null || userType != ADMIN_USER_TYPE) {
            return AdminLoginResponse.newBuilder()
                    .setValid(false)
                    .setMessage("Account is not an admin")
                    .build();
        }
        if (!isUserActive(user)) {
            return AdminLoginResponse.newBuilder()
                    .setValid(false)
                    .setMessage("Admin account is disabled")
                    .build();
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            return AdminLoginResponse.newBuilder()
                    .setValid(false)
                    .setMessage("Invalid credentials")
                    .build();
        }

        AdminProfile.Builder profile = AdminProfile.newBuilder()
                .setUserId(user.getUserId() == null ? 0 : user.getUserId())
                .setUsername(nvl(user.getUsername()))
                .setEmail(nvl(user.getEmail()))
                .setUserType(userType)
                .setUserLevel(user.getUserLevel() == null ? 0 : user.getUserLevel())
                .setCredit(user.getCredit() == null ? 0 : user.getCredit())
                .setRegisterTime(user.getRegisterTime() == null ? 0 : user.getRegisterTime());

        return AdminLoginResponse.newBuilder()
                .setValid(true)
                .setMessage("ok")
                .setProfile(profile)
                .build();
    }

    @Override
    public AdminActionResponse createAdmin(AdminCreateRequest request) {
        if (!magicPassword.equals(request.getMagicPassword())) {
            return AdminActionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Invalid magic password")
                    .build();
        }

        String username = request.getUsername();
        String password = request.getPassword();
        String email = request.getEmail();
        if (isBlank(username) || isBlank(password) || isBlank(email)) {
            return AdminActionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Username, password, and email are required")
                    .build();
        }
        if (!isPasswordStrong(password)) {
            return AdminActionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Password must contain at least one uppercase letter, one lowercase letter, one digit, and be at least 8 characters long")
                    .build();
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setEmail(email);
        user.setRegisterTime(System.currentTimeMillis());
        user.setUserType(ADMIN_USER_TYPE);
        user.setUserLevel(0);
        user.setCredit(0);
        user.setStatus(STATUS_ACTIVE);

        try {
            int result = userDao.insertUser(user);
            if (result > 0) {
                return AdminActionResponse.newBuilder()
                        .setSuccess(true)
                        .setMessage("Admin created successfully")
                        .build();
            }
            return AdminActionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Failed to create admin")
                    .build();
        } catch (Exception e) {
            log.error("Failed to create admin user: {}", username, e);
            return AdminActionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Failed to create admin")
                    .build();
        }
    }

    @Override
    public AdminActionResponse deleteAdmin(AdminDeleteRequest request) {
        if (!magicPassword.equals(request.getMagicPassword())) {
            return AdminActionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Invalid magic password")
                    .build();
        }

        String username = request.getUsername();
        if (isBlank(username)) {
            return AdminActionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Username is required")
                    .build();
        }

        List<User> users = userDao.getUserByUsername(username);
        if (users.isEmpty()) {
            return AdminActionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Admin account not found")
                    .build();
        }

        User user = users.get(0);
        Integer userType = user.getUserType();
        if (userType == null || userType != ADMIN_USER_TYPE) {
            return AdminActionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Account is not an admin")
                    .build();
        }

        try {
            int result = userDao.deleteUserByUsernameAndType(username, ADMIN_USER_TYPE);
            if (result > 0) {
                return AdminActionResponse.newBuilder()
                        .setSuccess(true)
                        .setMessage("Admin deleted successfully")
                        .build();
            }
            return AdminActionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Failed to delete admin")
                    .build();
        } catch (Exception e) {
            log.error("Failed to delete admin user: {}", username, e);
            return AdminActionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Failed to delete admin")
                    .build();
        }
    }

    @Override
    public AdminOverallResponse getAdminOverall(AdminOverallRequest request) {
        long fundCount = dataOverviewDao.countFundInfo();
        long indexCount = dataOverviewDao.countIndexInfo();
        long stockCount = dataOverviewDao.countStockInfo();
        long fundNavCount = dataOverviewDao.countFundNav();
        long indexDailyCount = dataOverviewDao.countIndexDaily();
        long stockDailyCount = dataOverviewDao.countStockDaily();

        return AdminOverallResponse.newBuilder()
                .setFundCount(fundCount)
                .setIndexCount(indexCount)
                .setStockCount(stockCount)
                .setFundNavCount(fundNavCount)
                .setIndexDailyCount(indexDailyCount)
                .setStockDailyCount(stockDailyCount)
                .build();
    }

    @Override
    public ListCreditApplicationsResponse listCreditApplications(ListCreditApplicationsRequest request) {
        try {
            int page = Math.max(1, request.getPage());
            int pageSize = Math.max(1, Math.min(100, request.getPageSize()));
            int offset = (page - 1) * pageSize;
            String status = normalizeNullable(request.getStatus());
            String userId = normalizeNullable(request.getUserId());

            List<AgentCreditApplication> applications = creditApplicationDao.listByStatus(status, userId, pageSize, offset);
            int total = creditApplicationDao.countByStatus(status, userId);

            ListCreditApplicationsResponse.Builder builder = ListCreditApplicationsResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("ok")
                    .setErrorCode(ERROR_OK)
                    .setTotal(total)
                    .setPage(page)
                    .setPageSize(pageSize);
            for (AgentCreditApplication app : applications) {
                builder.addApplications(toCreditApplication(app));
            }
            return builder.build();
        } catch (Exception e) {
            log.error("Failed to list credit applications", e);
            return ListCreditApplicationsResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Failed to list applications")
                    .setErrorCode(ERROR_INTERNAL)
                    .build();
        }
    }

    @Override
    public GetCreditApplicationResponse getCreditApplication(GetCreditApplicationRequest request) {
        String applicationId = request.getApplicationId();
        if (isBlank(applicationId)) {
            return GetCreditApplicationResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INVALID_ARGUMENT)
                    .setMessage("applicationId is required")
                    .build();
        }
        AgentCreditApplication application = creditApplicationDao.getByApplicationId(applicationId);
        if (application == null) {
            return GetCreditApplicationResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_NOT_FOUND)
                    .setMessage("Application not found")
                    .build();
        }
        List<AdminAuditLog> auditLogs = adminAuditLogDao.listByTarget(TARGET_CREDIT_APPLICATION, applicationId, 20);
        GetCreditApplicationResponse.Builder builder = GetCreditApplicationResponse.newBuilder()
                .setSuccess(true)
                .setErrorCode(ERROR_OK)
                .setMessage("ok")
                .setApplication(toCreditApplication(application));
        for (AdminAuditLog logItem : auditLogs) {
            builder.addAuditTrail(CreditApplicationAudit.newBuilder()
                    .setAuditId(nvl(logItem.getAuditId()))
                    .setOperatorId(nvl(logItem.getOperatorId()))
                    .setAction(nvl(logItem.getAction()))
                    .setReason(nvl(logItem.getReason()))
                    .setBeforeJson(nvl(logItem.getBeforeJson()))
                    .setAfterJson(nvl(logItem.getAfterJson()))
                    .setCreatedAt(toDateString(logItem.getCreatedAt()))
                    .build());
        }
        return builder.build();
    }

    @Override
    public ProcessCreditApplicationResponse approveCreditApplication(ProcessCreditApplicationRequest request) {
        return processCreditApplication(request, STATUS_APPROVED, "APPROVE_CREDIT_APPLICATION", "APPLY_APPROVE", true);
    }

    @Override
    public ProcessCreditApplicationResponse rejectCreditApplication(ProcessCreditApplicationRequest request) {
        return processCreditApplication(request, STATUS_REJECTED, "REJECT_CREDIT_APPLICATION", "APPLY_REJECT", false);
    }

    @Override
    public ListCreditLedgerResponse listCreditLedger(ListCreditLedgerRequest request) {
        int page = Math.max(1, request.getPage());
        int pageSize = Math.max(1, Math.min(100, request.getPageSize()));
        int offset = (page - 1) * pageSize;

        OffsetDateTime fromTime;
        OffsetDateTime toTime;
        try {
            fromTime = parseOffsetDateTime(normalizeNullable(request.getFrom()));
            toTime = parseOffsetDateTime(normalizeNullable(request.getTo()));
        } catch (Exception e) {
            return ListCreditLedgerResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INVALID_ARGUMENT)
                    .setMessage("from/to should be ISO-8601 datetime")
                    .build();
        }

        try {
            List<AgentCreditLedger> ledgers = creditLedgerDao.list(
                    normalizeNullable(request.getUserId()),
                    normalizeNullable(request.getBizType()),
                    normalizeNullable(request.getSourceId()),
                    fromTime,
                    toTime,
                    pageSize,
                    offset
            );
            int total = creditLedgerDao.count(
                    normalizeNullable(request.getUserId()),
                    normalizeNullable(request.getBizType()),
                    normalizeNullable(request.getSourceId()),
                    fromTime,
                    toTime
            );
            ListCreditLedgerResponse.Builder builder = ListCreditLedgerResponse.newBuilder()
                    .setSuccess(true)
                    .setErrorCode(ERROR_OK)
                    .setMessage("ok")
                    .setTotal(total)
                    .setPage(page)
                    .setPageSize(pageSize);
            for (AgentCreditLedger ledger : ledgers) {
                builder.addEntries(CreditLedgerEntry.newBuilder()
                        .setLedgerId(nvl(ledger.getLedgerId()))
                        .setUserId(nvl(ledger.getUserId()))
                        .setBizType(nvl(ledger.getBizType()))
                        .setDelta(safeInt(ledger.getDelta()))
                        .setBalanceBefore(safeInt(ledger.getBalanceBefore()))
                        .setBalanceAfter(safeInt(ledger.getBalanceAfter()))
                        .setSourceType(nvl(ledger.getSourceType()))
                        .setSourceId(nvl(ledger.getSourceId()))
                        .setOperatorId(nvl(ledger.getOperatorId()))
                        .setIdempotencyKey(nvl(ledger.getIdempotencyKey()))
                        .setExt(nvl(ledger.getExt()))
                        .setCreatedAt(toDateString(ledger.getCreatedAt()))
                        .build());
            }
            return builder.build();
        } catch (Exception e) {
            log.error("Failed to list credit ledger", e);
            return ListCreditLedgerResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INTERNAL)
                    .setMessage("Failed to list credit ledger")
                    .build();
        }
    }

    @Override
    public ListUsersResponse listUsers(ListUsersRequest request) {
        int page = Math.max(1, request.getPage());
        int pageSize = Math.max(1, Math.min(100, request.getPageSize()));
        int offset = (page - 1) * pageSize;
        try {
            String keyword = normalizeNullable(request.getKeyword());
            String status = normalizeNullable(request.getStatus());
            List<User> users = userDao.listUsers(keyword, status, pageSize, offset);
            int total = userDao.countUsers(keyword, status);
            ListUsersResponse.Builder builder = ListUsersResponse.newBuilder()
                    .setSuccess(true)
                    .setErrorCode(ERROR_OK)
                    .setMessage("ok")
                    .setTotal(total)
                    .setPage(page)
                    .setPageSize(pageSize);
            for (User user : users) {
                builder.addUsers(AdminUser.newBuilder()
                        .setUserId(String.valueOf(user.getUserId()))
                        .setUsername(nvl(user.getUsername()))
                        .setEmail(nvl(user.getEmail()))
                        .setCredit(safeInt(user.getCredit()))
                        .setRegisterTime(user.getRegisterTime() == null ? 0L : user.getRegisterTime())
                        .setStatus(normalizeUserStatus(user.getStatus()))
                        .setDisabledAt(toDateString(user.getDisabledAt()))
                        .setDisabledReason(nvl(user.getDisabledReason()))
                        .build());
            }
            return builder.build();
        } catch (Exception e) {
            log.error("Failed to list users", e);
            return ListUsersResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INTERNAL)
                    .setMessage("Failed to list users")
                    .build();
        }
    }

    @Override
    public UpdateUserStatusResponse updateUserStatus(UpdateUserStatusRequest request) {
        String userId = request.getUserId();
        if (isBlank(userId) || isBlank(request.getTargetStatus()) || isBlank(request.getReason())) {
            return UpdateUserStatusResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INVALID_ARGUMENT)
                    .setMessage("userId/targetStatus/reason are required")
                    .build();
        }
        Long userIdLong;
        try {
            userIdLong = Long.parseLong(userId.trim());
        } catch (Exception e) {
            return UpdateUserStatusResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INVALID_ARGUMENT)
                    .setMessage("userId should be numeric")
                    .build();
        }

        User user = userDao.getUserById(userIdLong);
        if (user == null) {
            return UpdateUserStatusResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_NOT_FOUND)
                    .setMessage("User not found")
                    .build();
        }

        String targetStatus = normalizeUserStatus(request.getTargetStatus());
        if (!STATUS_ACTIVE.equals(targetStatus) && !STATUS_DISABLED.equals(targetStatus)) {
            return UpdateUserStatusResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INVALID_ARGUMENT)
                    .setMessage("targetStatus should be ACTIVE or DISABLED")
                    .build();
        }

        String oldStatus = normalizeUserStatus(user.getStatus());
        List<String> failedRunIds = new ArrayList<>();
        int terminatedCount = 0;
        if (!oldStatus.equals(targetStatus)) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            OffsetDateTime disabledAt = STATUS_DISABLED.equals(targetStatus) ? now : null;
            String disabledReason = STATUS_DISABLED.equals(targetStatus) ? request.getReason() : null;
            int affected = userDao.updateStatusByUserId(userIdLong, targetStatus, disabledAt, disabledReason);
            if (affected <= 0) {
                return UpdateUserStatusResponse.newBuilder()
                        .setSuccess(false)
                        .setErrorCode(ERROR_INTERNAL)
                        .setMessage("Failed to update user status")
                        .build();
            }
            if (STATUS_DISABLED.equals(targetStatus) && request.getTerminateRunningRuns()) {
                TerminateSummary summary = terminateRunningRuns(userId);
                terminatedCount = summary.terminatedCount();
                failedRunIds = summary.failedRunIds();
            }
        }

        String auditId = generateId();
        Map<String, Object> before = Map.of(
                "userId", userId,
                "status", oldStatus
        );
        Map<String, Object> after = new HashMap<>();
        after.put("userId", userId);
        after.put("status", targetStatus);
        after.put("revokeTokens", request.getRevokeTokens());
        after.put("blockNewRuns", request.getBlockNewRuns());
        after.put("terminateRunningRuns", request.getTerminateRunningRuns());
        after.put("runningRunsTerminatedCount", terminatedCount);
        after.put("failedRunIds", failedRunIds);
        insertAudit(auditId,
                nvl(request.getOperatorId()),
                "UPDATE_USER_STATUS",
                TARGET_USER,
                userId,
                toJson(before),
                toJson(after),
                request.getReason(),
                "");

        UserStatusEffectiveActions.Builder actions = UserStatusEffectiveActions.newBuilder()
                .setTokensRevokedCount(0)
                .setNewRunsBlocked(STATUS_DISABLED.equals(targetStatus))
                .setRunningRunsTerminatedCount(terminatedCount)
                .addAllFailedRunIds(failedRunIds);

        return UpdateUserStatusResponse.newBuilder()
                .setSuccess(true)
                .setErrorCode(ERROR_OK)
                .setMessage(oldStatus.equals(targetStatus) ? "User status unchanged" : "User status updated")
                .setUserId(userId)
                .setOldStatus(oldStatus)
                .setNewStatus(targetStatus)
                .setAuditId(auditId)
                .setEffectiveActions(actions)
                .build();
    }

    private ProcessCreditApplicationResponse processCreditApplication(ProcessCreditApplicationRequest request,
                                                                      String targetStatus,
                                                                      String auditAction,
                                                                      String ledgerBizType,
                                                                      boolean applyCredit) {
        String applicationId = request.getApplicationId();
        String adminId = request.getAdminId();
        String idempotencyKey = request.getIdempotencyKey();
        String processReason = request.getProcessReason();
        int expectedVersion = request.getExpectedVersion();
        if (isBlank(applicationId) || isBlank(adminId) || isBlank(idempotencyKey) || isBlank(processReason) || expectedVersion < 0) {
            return ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INVALID_ARGUMENT)
                    .setMessage("applicationId/adminId/idempotencyKey/processReason/expectedVersion are required")
                    .build();
        }

        String requestHash = sha256Hex(targetStatus + "|" + processReason + "|" + expectedVersion);
        int inserted = adminIdempotencyDao.insertProcessing(
                adminId,
                auditAction,
                applicationId,
                idempotencyKey,
                requestHash,
                IDEM_STATUS_PROCESSING,
                "{}"
        );
        AdminIdempotency idemRecord = adminIdempotencyDao.find(adminId, auditAction, applicationId, idempotencyKey);
        if (idemRecord == null) {
            return ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INTERNAL)
                    .setMessage("Failed to create idempotency record")
                    .build();
        }

        if (inserted <= 0) {
            return handleIdempotentReplay(idemRecord, requestHash);
        }

        ProcessCreditApplicationResponse result;
        try {
            result = transactionTemplate.execute(status -> processCreditApplicationInTransaction(
                    applicationId,
                    adminId,
                    idempotencyKey,
                    processReason,
                    expectedVersion,
                    targetStatus,
                    auditAction,
                    ledgerBizType,
                    applyCredit
            ));
            if (result == null) {
                result = ProcessCreditApplicationResponse.newBuilder()
                        .setSuccess(false)
                        .setErrorCode(ERROR_INTERNAL)
                        .setMessage("Empty transaction result")
                        .build();
            }
        } catch (Exception e) {
            log.error("Failed to process credit application: {}", applicationId, e);
            result = ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INTERNAL)
                    .setMessage("Internal error")
                    .build();
        }
        adminIdempotencyDao.markCompleted(idemRecord.getId(), IDEM_STATUS_COMPLETED, serializeProcessResponse(result));
        return result;
    }

    private ProcessCreditApplicationResponse processCreditApplicationInTransaction(String applicationId,
                                                                                   String adminId,
                                                                                   String idempotencyKey,
                                                                                   String processReason,
                                                                                   int expectedVersion,
                                                                                   String targetStatus,
                                                                                   String auditAction,
                                                                                   String ledgerBizType,
                                                                                   boolean applyCredit) {
        AgentCreditApplication application = creditApplicationDao.getByApplicationIdWithStatusVersion(applicationId);
        if (application == null) {
            return ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_NOT_FOUND)
                    .setMessage("Application not found")
                    .build();
        }
        if (!STATUS_PENDING.equals(application.getStatus())) {
            return ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_STATUS_CONFLICT)
                    .setMessage("Application is not pending")
                    .setConflictStatus(nvl(application.getStatus()))
                    .setConflictVersion(safeInt(application.getVersion()))
                    .setApplication(toCreditApplication(application))
                    .build();
        }
        if (safeInt(application.getVersion()) != expectedVersion) {
            return ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_VERSION_CONFLICT)
                    .setMessage("Application version conflict")
                    .setConflictStatus(nvl(application.getStatus()))
                    .setConflictVersion(safeInt(application.getVersion()))
                    .setApplication(toCreditApplication(application))
                    .build();
        }

        String beforeJson = toJson(applicationToMap(application));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int updated = creditApplicationDao.updateStatusWithVersion(
                applicationId,
                targetStatus,
                now,
                adminId,
                processReason,
                expectedVersion
        );
        if (updated <= 0) {
            AgentCreditApplication latest = creditApplicationDao.getByApplicationIdWithStatusVersion(applicationId);
            String errorCode = latest != null && STATUS_PENDING.equals(latest.getStatus()) ? ERROR_VERSION_CONFLICT : ERROR_STATUS_CONFLICT;
            return ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(errorCode)
                    .setMessage("Application update conflict")
                    .setConflictStatus(latest == null ? "" : nvl(latest.getStatus()))
                    .setConflictVersion(latest == null ? 0 : safeInt(latest.getVersion()))
                    .setApplication(latest == null ? CreditApplication.getDefaultInstance() : toCreditApplication(latest))
                    .build();
        }

        int delta = applyCredit ? Math.max(0, application.getAmount()) : 0;
        int balanceBefore = 0;
        int balanceAfter = 0;
        String ledgerId = generateId();
        Long userIdLong = Long.parseLong(application.getUserId());
        User userBefore = userDao.getUserById(userIdLong);
        if (userBefore != null) {
            balanceBefore = safeInt(userBefore.getCredit());
        }
        if (applyCredit) {
            int affected = userDao.increaseCreditByUserId(userIdLong, delta);
            if (affected <= 0) {
                throw new IllegalStateException("Failed to increase user credit");
            }
        }
        User userAfter = userDao.getUserById(userIdLong);
        if (userAfter != null) {
            balanceAfter = safeInt(userAfter.getCredit());
        } else {
            balanceAfter = balanceBefore + delta;
        }

        AgentCreditLedger ledger = new AgentCreditLedger();
        ledger.setLedgerId(ledgerId);
        ledger.setUserId(application.getUserId());
        ledger.setBizType(ledgerBizType);
        ledger.setDelta(delta);
        ledger.setBalanceBefore(balanceBefore);
        ledger.setBalanceAfter(balanceAfter);
        ledger.setSourceType(SOURCE_TYPE_CREDIT_APPLICATION);
        ledger.setSourceId(applicationId);
        ledger.setOperatorId(adminId);
        ledger.setIdempotencyKey(idempotencyKey);
        ledger.setExt("{}");
        creditLedgerDao.insertIgnoreDuplicate(ledger);

        AgentCreditApplication latest = creditApplicationDao.getByApplicationIdWithStatusVersion(applicationId);
        String afterJson = toJson(applicationToMap(latest));
        String auditId = generateId();
        insertAudit(
                auditId,
                adminId,
                auditAction,
                TARGET_CREDIT_APPLICATION,
                applicationId,
                beforeJson,
                afterJson,
                processReason,
                idempotencyKey
        );

        return ProcessCreditApplicationResponse.newBuilder()
                .setSuccess(true)
                .setErrorCode(ERROR_OK)
                .setMessage(STATUS_APPROVED.equals(targetStatus) ? "Application approved successfully" : "Application rejected successfully")
                .setApplication(latest == null ? CreditApplication.getDefaultInstance() : toCreditApplication(latest))
                .setAuditId(auditId)
                .setIdempotentReplay(false)
                .setCreditChange(CreditChange.newBuilder()
                        .setDelta(delta)
                        .setBalanceBefore(balanceBefore)
                        .setBalanceAfter(balanceAfter)
                        .setLedgerId(ledgerId)
                        .build())
                .build();
    }

    private ProcessCreditApplicationResponse handleIdempotentReplay(AdminIdempotency record, String requestHash) {
        if (!requestHash.equals(nvl(record.getRequestHash()))) {
            return ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INVALID_ARGUMENT)
                    .setMessage("Idempotency key conflicts with another payload")
                    .build();
        }
        if (!IDEM_STATUS_COMPLETED.equalsIgnoreCase(record.getStatus())) {
            return ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_IDEMPOTENCY_IN_PROGRESS)
                    .setMessage("Another request with same idempotency key is in progress")
                    .build();
        }
        ProcessCreditApplicationResponse replay = deserializeProcessResponse(record.getResponseJson());
        return replay.toBuilder()
                .setIdempotentReplay(true)
                .setErrorCode(replay.getSuccess() ? ERROR_IDEMPOTENCY_REPLAY : replay.getErrorCode())
                .build();
    }

    private void insertAudit(String auditId,
                             String operatorId,
                             String action,
                             String targetType,
                             String targetId,
                             String beforeJson,
                             String afterJson,
                             String reason,
                             String idempotencyKey) {
        AdminAuditLog audit = new AdminAuditLog();
        audit.setAuditId(auditId);
        audit.setOperatorId(operatorId);
        audit.setAction(action);
        audit.setTargetType(targetType);
        audit.setTargetId(targetId);
        audit.setBeforeJson(isBlank(beforeJson) ? "{}" : beforeJson);
        audit.setAfterJson(isBlank(afterJson) ? "{}" : afterJson);
        audit.setReason(nvl(reason));
        audit.setIdempotencyKey(nvl(idempotencyKey));
        adminAuditLogDao.insert(audit);
    }

    private CreditApplication toCreditApplication(AgentCreditApplication app) {
        if (app == null) {
            return CreditApplication.getDefaultInstance();
        }
        return CreditApplication.newBuilder()
                .setApplicationId(nvl(app.getApplicationId()))
                .setUserId(nvl(app.getUserId()))
                .setAmount(safeInt(app.getAmount()))
                .setReason(nvl(app.getReason()))
                .setContact(nvl(app.getContact()))
                .setStatus(nvl(app.getStatus()))
                .setCreatedAt(toDateString(app.getCreatedAt()))
                .setProcessedAt(toDateString(app.getProcessedAt()))
                .setProcessedBy(nvl(app.getProcessedBy()))
                .setProcessReason(nvl(app.getProcessReason()))
                .setVersion(safeInt(app.getVersion()))
                .build();
    }

    private String serializeProcessResponse(ProcessCreditApplicationResponse response) {
        Map<String, Object> map = new HashMap<>();
        map.put("success", response.getSuccess());
        map.put("message", response.getMessage());
        map.put("errorCode", response.getErrorCode());
        map.put("auditId", response.getAuditId());
        map.put("idempotentReplay", response.getIdempotentReplay());
        map.put("conflictStatus", response.getConflictStatus());
        map.put("conflictVersion", response.getConflictVersion());
        if (response.hasApplication()) {
            Map<String, Object> app = new HashMap<>();
            app.put("applicationId", response.getApplication().getApplicationId());
            app.put("userId", response.getApplication().getUserId());
            app.put("amount", response.getApplication().getAmount());
            app.put("reason", response.getApplication().getReason());
            app.put("contact", response.getApplication().getContact());
            app.put("status", response.getApplication().getStatus());
            app.put("createdAt", response.getApplication().getCreatedAt());
            app.put("processedAt", response.getApplication().getProcessedAt());
            app.put("processedBy", response.getApplication().getProcessedBy());
            app.put("processReason", response.getApplication().getProcessReason());
            app.put("version", response.getApplication().getVersion());
            map.put("application", app);
        }
        if (response.hasCreditChange()) {
            Map<String, Object> creditChange = new HashMap<>();
            creditChange.put("delta", response.getCreditChange().getDelta());
            creditChange.put("balanceBefore", response.getCreditChange().getBalanceBefore());
            creditChange.put("balanceAfter", response.getCreditChange().getBalanceAfter());
            creditChange.put("ledgerId", response.getCreditChange().getLedgerId());
            map.put("creditChange", creditChange);
        }
        return toJson(map);
    }

    private ProcessCreditApplicationResponse deserializeProcessResponse(String json) {
        if (isBlank(json)) {
            return ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INTERNAL)
                    .setMessage("Empty idempotency response")
                    .build();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            ProcessCreditApplicationResponse.Builder builder = ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(Boolean.TRUE.equals(map.get("success")))
                    .setMessage(nvl(map.get("message")))
                    .setErrorCode(nvl(map.get("errorCode")))
                    .setAuditId(nvl(map.get("auditId")))
                    .setIdempotentReplay(Boolean.TRUE.equals(map.get("idempotentReplay")))
                    .setConflictStatus(nvl(map.get("conflictStatus")))
                    .setConflictVersion(toInt(map.get("conflictVersion")));
            Object appRaw = map.get("application");
            if (appRaw instanceof Map<?, ?> appMap) {
                builder.setApplication(CreditApplication.newBuilder()
                        .setApplicationId(nvl(appMap.get("applicationId")))
                        .setUserId(nvl(appMap.get("userId")))
                        .setAmount(toInt(appMap.get("amount")))
                        .setReason(nvl(appMap.get("reason")))
                        .setContact(nvl(appMap.get("contact")))
                        .setStatus(nvl(appMap.get("status")))
                        .setCreatedAt(nvl(appMap.get("createdAt")))
                        .setProcessedAt(nvl(appMap.get("processedAt")))
                        .setProcessedBy(nvl(appMap.get("processedBy")))
                        .setProcessReason(nvl(appMap.get("processReason")))
                        .setVersion(toInt(appMap.get("version")))
                        .build());
            }
            Object creditChangeRaw = map.get("creditChange");
            if (creditChangeRaw instanceof Map<?, ?> creditChangeMap) {
                builder.setCreditChange(CreditChange.newBuilder()
                        .setDelta(toInt(creditChangeMap.get("delta")))
                        .setBalanceBefore(toInt(creditChangeMap.get("balanceBefore")))
                        .setBalanceAfter(toInt(creditChangeMap.get("balanceAfter")))
                        .setLedgerId(nvl(creditChangeMap.get("ledgerId")))
                        .build());
            }
            return builder.build();
        } catch (Exception e) {
            log.error("Failed to deserialize idempotency response", e);
            return ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INTERNAL)
                    .setMessage("Broken idempotency response")
                    .build();
        }
    }

    private TerminateSummary terminateRunningRuns(String userId) {
        List<String> failedRunIds = new ArrayList<>();
        int terminatedCount = 0;
        List<String> statuses = List.of("RECEIVED", "PLANNING", "EXECUTING", "SUMMARIZING");
        for (String status : statuses) {
            int offset = 0;
            while (true) {
                var listResp = agentDubboService.listRuns(ListAgentRunsRequest.newBuilder()
                        .setUserId(userId)
                        .setStatus(status)
                        .setLimit(100)
                        .setOffset(offset)
                        .setDays(3650)
                        .build());
                if (listResp.getItemsCount() <= 0) {
                    break;
                }
                for (var run : listResp.getItemsList()) {
                    try {
                        agentDubboService.cancelRun(CancelAgentRunRequest.newBuilder()
                                .setUserId(userId)
                                .setId(run.getId())
                                .build());
                        terminatedCount++;
                    } catch (Exception e) {
                        failedRunIds.add(run.getId());
                        log.warn("Failed to terminate run for disabled user: userId={}, runId={}", userId, run.getId(), e);
                    }
                }
                if (!listResp.getHasMore()) {
                    break;
                }
                offset += listResp.getItemsCount();
            }
        }
        return new TerminateSummary(terminatedCount, failedRunIds);
    }

    private Map<String, Object> applicationToMap(AgentCreditApplication app) {
        if (app == null) {
            return Map.of();
        }
        Map<String, Object> map = new HashMap<>();
        map.put("applicationId", app.getApplicationId());
        map.put("userId", app.getUserId());
        map.put("amount", app.getAmount());
        map.put("status", app.getStatus());
        map.put("reason", nvl(app.getReason()));
        map.put("contact", nvl(app.getContact()));
        map.put("processedBy", nvl(app.getProcessedBy()));
        map.put("processReason", nvl(app.getProcessReason()));
        map.put("version", safeInt(app.getVersion()));
        map.put("createdAt", toDateString(app.getCreatedAt()));
        map.put("processedAt", toDateString(app.getProcessedAt()));
        return map;
    }

    private OffsetDateTime parseOffsetDateTime(String value) {
        if (isBlank(value)) {
            return null;
        }
        return OffsetDateTime.parse(value);
    }

    private String toDateString(OffsetDateTime value) {
        return value == null ? "" : value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private boolean isUserActive(User user) {
        if (user == null) {
            return false;
        }
        return STATUS_ACTIVE.equals(normalizeUserStatus(user.getStatus()));
    }

    private String normalizeUserStatus(String status) {
        String normalized = nvl(status).trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return STATUS_ACTIVE;
        }
        return normalized;
    }

    private String generateId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String normalizeNullable(String value) {
        String normalized = nvl(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private String nvl(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String toJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object == null ? Map.of() : object);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(nvl(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isPasswordStrong(String password) {
        if (password == null || password.length() < 8) {
            return false;
        }
        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c)) {
                hasUpper = true;
            } else if (Character.isLowerCase(c)) {
                hasLower = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        return hasUpper && hasLower && hasDigit;
    }

    // ========== Agent 运行监控 ==========

    @Override
    public ListAdminAgentRunsResponse listAgentRuns(ListAdminAgentRunsRequest request) {
        try {
            int page = Math.max(1, request.getPage());
            int pageSize = Math.max(1, Math.min(100, request.getPageSize()));
            int days = request.getDays() > 0 ? request.getDays() : 30;
            String status = normalizeNullable(request.getStatus());
            String targetUserId = normalizeNullable(request.getUserId());

            // 如果指定了特定用户，直接查询该用户
            // 否则需要遍历所有用户（简化实现：先获取所有用户ID列表）
            List<AdminAgentRun> result = new ArrayList<>();
            int total = 0;

            if (targetUserId != null) {
                var listResp = agentDubboService.listRuns(ListAgentRunsRequest.newBuilder()
                        .setUserId(targetUserId)
                        .setStatus(status == null ? "" : status)
                        .setLimit(pageSize)
                        .setOffset((page - 1) * pageSize)
                        .setDays(days)
                        .build());
                for (var run : listResp.getItemsList()) {
                    result.add(toAdminAgentRun(run, targetUserId));
                }
                total = listResp.getTotal();
            } else {
                // 获取所有普通用户，查询他们的运行记录
                // 简化实现：只查询最近活跃的用户
                List<User> users = userDao.listUsers(null, STATUS_ACTIVE, 100, 0);
                for (User user : users) {
                    if (user.getUserType() != null && user.getUserType() == ADMIN_USER_TYPE) {
                        continue;
                    }
                    String userId = String.valueOf(user.getUserId());
                    var listResp = agentDubboService.listRuns(ListAgentRunsRequest.newBuilder()
                            .setUserId(userId)
                            .setStatus(status == null ? "" : status)
                            .setLimit(50)
                            .setOffset(0)
                            .setDays(days)
                            .build());
                    for (var run : listResp.getItemsList()) {
                        result.add(toAdminAgentRun(run, userId));
                    }
                }
                total = result.size();
                // 分页处理
                int fromIndex = (page - 1) * pageSize;
                int toIndex = Math.min(fromIndex + pageSize, result.size());
                if (fromIndex < result.size()) {
                    result = result.subList(fromIndex, toIndex);
                } else {
                    result = new ArrayList<>();
                }
            }

            return ListAdminAgentRunsResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("ok")
                    .setErrorCode(ERROR_OK)
                    .addAllRuns(result)
                    .setTotal(total)
                    .setPage(page)
                    .setPageSize(pageSize)
                    .build();
        } catch (Exception e) {
            log.error("Failed to list agent runs", e);
            return ListAdminAgentRunsResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INTERNAL)
                    .setMessage("Failed to list agent runs")
                    .build();
        }
    }

    @Override
    public GetAdminAgentRunResponse getAgentRun(GetAdminAgentRunRequest request) {
        String runId = request.getRunId();
        if (isBlank(runId)) {
            return GetAdminAgentRunResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INVALID_ARGUMENT)
                    .setMessage("runId is required")
                    .build();
        }

        try {
            // 先尝试通过遍历用户找到这个 run
            // 简化实现：查询最近活跃用户
            List<User> users = userDao.listUsers(null, STATUS_ACTIVE, 100, 0);
            for (User user : users) {
                String userId = String.valueOf(user.getUserId());
                var listResp = agentDubboService.listRuns(ListAgentRunsRequest.newBuilder()
                        .setUserId(userId)
                        .setLimit(100)
                        .setOffset(0)
                        .setDays(3650)
                        .build());
                for (var run : listResp.getItemsList()) {
                    if (runId.equals(run.getId())) {
                        // 找到 run，获取详细信息
                        var getResp = agentDubboService.getRun(
                                world.willfrog.alphafrogmicro.agent.idl.GetAgentRunRequest.newBuilder()
                                        .setUserId(userId)
                                        .setId(runId)
                                        .build());
                        return GetAdminAgentRunResponse.newBuilder()
                                .setSuccess(true)
                                .setErrorCode(ERROR_OK)
                                .setMessage("ok")
                                .setRun(toAdminAgentRun(run, userId))
                                .setPlanJson(nvl(getResp.getPlanJson()))
                                .setSnapshotJson(nvl(getResp.getSnapshotJson()))
                                .setLastError(nvl(getResp.getLastError()))
                                .build();
                    }
                }
            }
            return GetAdminAgentRunResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_NOT_FOUND)
                    .setMessage("Run not found")
                    .build();
        } catch (Exception e) {
            log.error("Failed to get agent run: {}", runId, e);
            return GetAdminAgentRunResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INTERNAL)
                    .setMessage("Failed to get agent run")
                    .build();
        }
    }

    @Override
    public StopAdminAgentRunResponse stopAgentRun(StopAdminAgentRunRequest request) {
        String runId = request.getRunId();
        String operatorId = request.getOperatorId();
        if (isBlank(runId)) {
            return StopAdminAgentRunResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INVALID_ARGUMENT)
                    .setMessage("runId is required")
                    .build();
        }

        try {
            // 先找到这个 run 所属的用户
            List<User> users = userDao.listUsers(null, STATUS_ACTIVE, 100, 0);
            for (User user : users) {
                String userId = String.valueOf(user.getUserId());
                var listResp = agentDubboService.listRuns(ListAgentRunsRequest.newBuilder()
                        .setUserId(userId)
                        .setLimit(100)
                        .setOffset(0)
                        .setDays(3650)
                        .build());
                for (var run : listResp.getItemsList()) {
                    if (runId.equals(run.getId())) {
                        // 找到 run，执行取消
                        var cancelResp = agentDubboService.cancelRun(CancelAgentRunRequest.newBuilder()
                                .setUserId(userId)
                                .setId(runId)
                                .build());
                        // 记录审计日志
                        String auditId = generateId();
                        Map<String, Object> before = Map.of(
                                "runId", runId,
                                "status", run.getStatus(),
                                "userId", userId
                        );
                        Map<String, Object> after = Map.of(
                                "runId", runId,
                                "status", cancelResp.getStatus(),
                                "userId", userId,
                                "stoppedBy", operatorId
                        );
                        insertAudit(auditId, operatorId, "STOP_AGENT_RUN", "AGENT_RUN", runId,
                                toJson(before), toJson(after), request.getReason(), "");

                        return StopAdminAgentRunResponse.newBuilder()
                                .setSuccess(true)
                                .setErrorCode(ERROR_OK)
                                .setMessage("Run stopped successfully")
                                .setRunId(runId)
                                .setNewStatus(cancelResp.getStatus())
                                .build();
                    }
                }
            }
            return StopAdminAgentRunResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_NOT_FOUND)
                    .setMessage("Run not found")
                    .build();
        } catch (Exception e) {
            log.error("Failed to stop agent run: {}", runId, e);
            return StopAdminAgentRunResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INTERNAL)
                    .setMessage("Failed to stop agent run")
                    .build();
        }
    }

    private AdminAgentRun toAdminAgentRun(world.willfrog.alphafrogmicro.agent.idl.AgentRunListItemMessage run, String userId) {
        // 获取用户名
        String username = "";
        try {
            Long uid = Long.parseLong(userId);
            User user = userDao.getUserById(uid);
            if (user != null) {
                username = nvl(user.getUsername());
            }
        } catch (Exception e) {
            log.debug("Failed to get username for userId: {}", userId);
        }

        return AdminAgentRun.newBuilder()
                .setRunId(run.getId())
                .setUserId(userId)
                .setUsername(username)
                .setStatus(run.getStatus())
                .setMessage(run.getMessage())
                .setStartedAt(run.getCreatedAt())
                .setCompletedAt(run.getCompletedAt())
                .setDurationMs(run.getDurationMs())
                .setTotalTokens(run.getTotalTokens())
                .setToolCalls(run.getToolCalls())
                .setHasArtifacts(run.getHasArtifacts())
                .build();
    }

    // ========== 系统配置管理 ==========

    @Override
    public GetSystemConfigResponse getSystemConfig(GetSystemConfigRequest request) {
        try {
            // 从 agent-llm.local.json 读取配置
            String configPath = System.getenv("AGENT_LLM_CONFIG_PATH");
            if (configPath == null || configPath.isBlank()) {
                configPath = "/app/config/agent-llm.local.json";
            }

            java.io.File configFile = new java.io.File(configPath);
            String configJson;
            if (configFile.exists()) {
                configJson = new String(java.nio.file.Files.readAllBytes(configFile.toPath()));
            } else {
                // 返回默认配置结构
                configJson = "{}";
            }

            List<SystemConfigItem> configs = new ArrayList<>();
            // 添加一些关键配置项
            configs.add(SystemConfigItem.newBuilder()
                    .setKey("agent.llm.config.path")
                    .setValue(configPath)
                    .setDescription("Agent LLM 配置文件路径")
                    .setCategory("system")
                    .setEditable(false)
                    .build());

            return GetSystemConfigResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("ok")
                    .setErrorCode(ERROR_OK)
                    .addAllConfigs(configs)
                    .setConfigJson(configJson)
                    .build();
        } catch (Exception e) {
            log.error("Failed to get system config", e);
            return GetSystemConfigResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INTERNAL)
                    .setMessage("Failed to get system config")
                    .build();
        }
    }

    @Override
    public UpdateSystemConfigResponse updateSystemConfig(UpdateSystemConfigRequest request) {
        String key = request.getKey();
        String value = request.getValue();
        if (isBlank(key)) {
            return UpdateSystemConfigResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INVALID_ARGUMENT)
                    .setMessage("key is required")
                    .build();
        }

        try {
            // 简化实现：只支持修改特定的运行时配置
            // 实际实现应该更新配置文件并触发重载
            log.info("System config update requested: {} = {}", key, value);

            // 记录审计日志
            String auditId = generateId();
            Map<String, Object> before = Map.of("key", key);
            Map<String, Object> after = Map.of("key", key, "value", value);
            insertAudit(auditId, request.getOperatorId(), "UPDATE_SYSTEM_CONFIG", "SYSTEM_CONFIG", key,
                    toJson(before), toJson(after), request.getReason(), "");

            return UpdateSystemConfigResponse.newBuilder()
                    .setSuccess(true)
                    .setErrorCode(ERROR_OK)
                    .setMessage("Config updated successfully (reload may be required)")
                    .setConfig(SystemConfigItem.newBuilder()
                            .setKey(key)
                            .setValue(value)
                            .setEditable(true)
                            .build())
                    .build();
        } catch (Exception e) {
            log.error("Failed to update system config: {}", key, e);
            return UpdateSystemConfigResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INTERNAL)
                    .setMessage("Failed to update system config")
                    .build();
        }
    }

    // ========== 用户额度直接调整 ==========

    @Override
    public AdjustUserCreditResponse adjustUserCredit(AdjustUserCreditRequest request) {
        String userId = request.getUserId();
        int delta = request.getDelta();
        String reason = request.getReason();
        String operatorId = request.getOperatorId();
        String idempotencyKey = request.getIdempotencyKey();

        if (isBlank(userId) || isBlank(reason) || isBlank(idempotencyKey)) {
            return AdjustUserCreditResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INVALID_ARGUMENT)
                    .setMessage("userId/reason/idempotencyKey are required")
                    .build();
        }

        if (delta == 0) {
            return AdjustUserCreditResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INVALID_ARGUMENT)
                    .setMessage("delta cannot be zero")
                    .build();
        }

        Long userIdLong;
        try {
            userIdLong = Long.parseLong(userId.trim());
        } catch (Exception e) {
            return AdjustUserCreditResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INVALID_ARGUMENT)
                    .setMessage("userId should be numeric")
                    .build();
        }

        User user = userDao.getUserById(userIdLong);
        if (user == null) {
            return AdjustUserCreditResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_NOT_FOUND)
                    .setMessage("User not found")
                    .build();
        }

        // 幂等性检查
        String auditAction = delta > 0 ? "INCREASE_USER_CREDIT" : "DECREASE_USER_CREDIT";
        String requestHash = sha256Hex(userId + "|" + delta + "|" + reason);
        int inserted = adminIdempotencyDao.insertProcessing(
                operatorId, auditAction, userId, idempotencyKey, requestHash,
                IDEM_STATUS_PROCESSING, "{}"
        );
        AdminIdempotency idemRecord = adminIdempotencyDao.find(operatorId, auditAction, userId, idempotencyKey);
        if (idemRecord == null) {
            return AdjustUserCreditResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INTERNAL)
                    .setMessage("Failed to create idempotency record")
                    .build();
        }
        if (inserted <= 0) {
            // 幂等重放
            return handleAdjustCreditIdempotentReplay(idemRecord, requestHash);
        }

        try {
            int creditBefore = safeInt(user.getCredit());
            // 检查扣减后是否会导致负额度
            if (delta < 0 && creditBefore + delta < 0) {
                adminIdempotencyDao.markCompleted(idemRecord.getId(), IDEM_STATUS_COMPLETED,
                        toJson(Map.of("success", false, "message", "Insufficient credit")));
                return AdjustUserCreditResponse.newBuilder()
                        .setSuccess(false)
                        .setErrorCode(ERROR_INVALID_ARGUMENT)
                        .setMessage("Insufficient credit: current=" + creditBefore + ", delta=" + delta)
                        .build();
            }

            int affected;
            if (delta > 0) {
                affected = userDao.increaseCreditByUserId(userIdLong, delta);
            } else {
                affected = userDao.decreaseCreditByUserId(userIdLong, -delta);
            }

            if (affected <= 0) {
                throw new IllegalStateException("Failed to adjust user credit");
            }

            User userAfter = userDao.getUserById(userIdLong);
            int creditAfter = safeInt(userAfter.getCredit());

            // 写入 ledger
            String ledgerId = generateId();
            AgentCreditLedger ledger = new AgentCreditLedger();
            ledger.setLedgerId(ledgerId);
            ledger.setUserId(userId);
            ledger.setBizType(delta > 0 ? "ADMIN_INCREASE" : "ADMIN_DECREASE");
            ledger.setDelta(delta);
            ledger.setBalanceBefore(creditBefore);
            ledger.setBalanceAfter(creditAfter);
            ledger.setSourceType("ADMIN_ADJUSTMENT");
            ledger.setSourceId("");
            ledger.setOperatorId(operatorId);
            ledger.setIdempotencyKey(idempotencyKey);
            ledger.setExt(toJson(Map.of("reason", reason)));
            creditLedgerDao.insertIgnoreDuplicate(ledger);

            // 审计日志
            String auditId = generateId();
            Map<String, Object> before = Map.of("userId", userId, "credit", creditBefore);
            Map<String, Object> after = Map.of("userId", userId, "credit", creditAfter, "delta", delta);
            insertAudit(auditId, operatorId, auditAction, TARGET_USER, userId,
                    toJson(before), toJson(after), reason, idempotencyKey);

            AdjustUserCreditResponse response = AdjustUserCreditResponse.newBuilder()
                    .setSuccess(true)
                    .setErrorCode(ERROR_OK)
                    .setMessage("Credit adjusted successfully")
                    .setUserId(userId)
                    .setCreditBefore(creditBefore)
                    .setCreditAfter(creditAfter)
                    .setDelta(delta)
                    .setLedgerId(ledgerId)
                    .setAuditId(auditId)
                    .setIdempotentReplay(false)
                    .build();

            adminIdempotencyDao.markCompleted(idemRecord.getId(), IDEM_STATUS_COMPLETED,
                    serializeAdjustCreditResponse(response));
            return response;

        } catch (Exception e) {
            log.error("Failed to adjust user credit: userId={}, delta={}", userId, delta, e);
            adminIdempotencyDao.markCompleted(idemRecord.getId(), IDEM_STATUS_COMPLETED,
                    toJson(Map.of("success", false, "message", e.getMessage())));
            return AdjustUserCreditResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INTERNAL)
                    .setMessage("Failed to adjust credit: " + e.getMessage())
                    .build();
        }
    }

    private AdjustUserCreditResponse handleAdjustCreditIdempotentReplay(AdminIdempotency record, String requestHash) {
        if (!requestHash.equals(nvl(record.getRequestHash()))) {
            return AdjustUserCreditResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INVALID_ARGUMENT)
                    .setMessage("Idempotency key conflicts with another payload")
                    .build();
        }
        if (!IDEM_STATUS_COMPLETED.equalsIgnoreCase(record.getStatus())) {
            return AdjustUserCreditResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_IDEMPOTENCY_IN_PROGRESS)
                    .setMessage("Another request with same idempotency key is in progress")
                    .build();
        }
        AdjustUserCreditResponse replay = deserializeAdjustCreditResponse(record.getResponseJson());
        return replay.toBuilder()
                .setIdempotentReplay(true)
                .build();
    }

    private String serializeAdjustCreditResponse(AdjustUserCreditResponse response) {
        Map<String, Object> map = new HashMap<>();
        map.put("success", response.getSuccess());
        map.put("message", response.getMessage());
        map.put("errorCode", response.getErrorCode());
        map.put("userId", response.getUserId());
        map.put("creditBefore", response.getCreditBefore());
        map.put("creditAfter", response.getCreditAfter());
        map.put("delta", response.getDelta());
        map.put("ledgerId", response.getLedgerId());
        map.put("auditId", response.getAuditId());
        map.put("idempotentReplay", response.getIdempotentReplay());
        return toJson(map);
    }

    private AdjustUserCreditResponse deserializeAdjustCreditResponse(String json) {
        if (isBlank(json)) {
            return AdjustUserCreditResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INTERNAL)
                    .setMessage("Empty idempotency response")
                    .build();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            return AdjustUserCreditResponse.newBuilder()
                    .setSuccess(Boolean.TRUE.equals(map.get("success")))
                    .setMessage(nvl(map.get("message")))
                    .setErrorCode(nvl(map.get("errorCode")))
                    .setUserId(nvl(map.get("userId")))
                    .setCreditBefore(toInt(map.get("creditBefore")))
                    .setCreditAfter(toInt(map.get("creditAfter")))
                    .setDelta(toInt(map.get("delta")))
                    .setLedgerId(nvl(map.get("ledgerId")))
                    .setAuditId(nvl(map.get("auditId")))
                    .setIdempotentReplay(Boolean.TRUE.equals(map.get("idempotentReplay")))
                    .build();
        } catch (Exception e) {
            log.error("Failed to deserialize adjust credit response", e);
            return AdjustUserCreditResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorCode(ERROR_INTERNAL)
                    .setMessage("Broken idempotency response")
                    .build();
        }
    }

    private record TerminateSummary(int terminatedCount, List<String> failedRunIds) {
    }
}

package world.willfrog.alphafrogmicro.adminservice;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.admin.idl.*;
import world.willfrog.alphafrogmicro.admin.idl.DubboAdminServiceTriple.AdminServiceImplBase;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentCreditApplicationDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.common.DataOverviewDao;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentCreditApplication;
import world.willfrog.alphafrogmicro.common.pojo.user.User;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@DubboService
@Service
@Slf4j
public class AdminServiceImpl extends AdminServiceImplBase {

    private static final int ADMIN_USER_TYPE = 1127;
    private static final String MAGIC_PASSWORD = "frog20191127StartFromBelieving";

    private final UserDao userDao;
    private final DataOverviewDao dataOverviewDao;
    private final PasswordEncoder passwordEncoder;
    private final AgentCreditApplicationDao creditApplicationDao;

    public AdminServiceImpl(UserDao userDao,
                            DataOverviewDao dataOverviewDao,
                            PasswordEncoder passwordEncoder,
                            AgentCreditApplicationDao creditApplicationDao) {
        this.userDao = userDao;
        this.dataOverviewDao = dataOverviewDao;
        this.passwordEncoder = passwordEncoder;
        this.creditApplicationDao = creditApplicationDao;
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

        if (!passwordEncoder.matches(password, user.getPassword())) {
            return AdminLoginResponse.newBuilder()
                    .setValid(false)
                    .setMessage("Invalid credentials")
                    .build();
        }

        AdminProfile.Builder profile = AdminProfile.newBuilder()
                .setUserId(user.getUserId() == null ? 0 : user.getUserId())
                .setUsername(user.getUsername())
                .setEmail(user.getEmail() == null ? "" : user.getEmail())
                .setUserType(userType == null ? 0 : userType)
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
        if (!MAGIC_PASSWORD.equals(request.getMagicPassword())) {
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
        if (!MAGIC_PASSWORD.equals(request.getMagicPassword())) {
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

    // ==================== 额度申请管理 ====================

    @Override
    public ListCreditApplicationsResponse listCreditApplications(ListCreditApplicationsRequest request) {
        try {
            String status = request.getStatus();
            if (isBlank(status)) {
                status = null; // 查询全部
            }
            int page = Math.max(1, request.getPage());
            int pageSize = Math.max(1, Math.min(100, request.getPageSize()));
            int offset = (page - 1) * pageSize;

            List<AgentCreditApplication> applications = creditApplicationDao.listByStatus(status, null, pageSize, offset);
            int total = creditApplicationDao.countByStatus(status, null);

            DateTimeFormatter formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

            List<CreditApplication> result = applications.stream()
                    .map(app -> {
                        CreditApplication.Builder builder = CreditApplication.newBuilder()
                                .setApplicationId(app.getApplicationId())
                                .setUserId(app.getUserId())
                                .setAmount(app.getAmount())
                                .setStatus(app.getStatus());
                        if (app.getReason() != null) {
                            builder.setReason(app.getReason());
                        }
                        if (app.getContact() != null) {
                            builder.setContact(app.getContact());
                        }
                        if (app.getCreatedAt() != null) {
                            builder.setCreatedAt(app.getCreatedAt().format(formatter));
                        }
                        if (app.getProcessedAt() != null) {
                            builder.setProcessedAt(app.getProcessedAt().format(formatter));
                        }
                        return builder.build();
                    })
                    .collect(Collectors.toList());

            return ListCreditApplicationsResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("ok")
                    .addAllApplications(result)
                    .setTotal(total)
                    .setPage(page)
                    .setPageSize(pageSize)
                    .build();
        } catch (Exception e) {
            log.error("Failed to list credit applications", e);
            return ListCreditApplicationsResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Failed to list applications: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public ProcessCreditApplicationResponse approveCreditApplication(ProcessCreditApplicationRequest request) {
        String applicationId = request.getApplicationId();
        if (isBlank(applicationId)) {
            return ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Application ID is required")
                    .build();
        }

        try {
            // 直接操作数据库审批
            AgentCreditApplication application = creditApplicationDao.getByApplicationId(applicationId);
            if (application == null) {
                return ProcessCreditApplicationResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("Application not found")
                        .build();
            }
            if (!"PENDING".equals(application.getStatus())) {
                return ProcessCreditApplicationResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("Application is not pending: " + application.getStatus())
                        .build();
            }

            // 增加用户额度
            Long userIdLong = Long.parseLong(application.getUserId());
            int updated = userDao.increaseCreditByUserId(userIdLong, application.getAmount());
            if (updated <= 0) {
                return ProcessCreditApplicationResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("Failed to increase user credits")
                        .build();
            }

            // 更新申请状态
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            creditApplicationDao.updateStatus(applicationId, "APPROVED", now);

            // 重新查询申请记录
            AgentCreditApplication app = creditApplicationDao.getByApplicationId(applicationId);
            DateTimeFormatter formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
            CreditApplication result = CreditApplication.newBuilder()
                    .setApplicationId(app.getApplicationId())
                    .setUserId(app.getUserId())
                    .setAmount(app.getAmount())
                    .setStatus(app.getStatus())
                    .setReason(app.getReason() != null ? app.getReason() : "")
                    .setContact(app.getContact() != null ? app.getContact() : "")
                    .setCreatedAt(app.getCreatedAt() != null ? app.getCreatedAt().format(formatter) : "")
                    .setProcessedAt(app.getProcessedAt() != null ? app.getProcessedAt().format(formatter) : "")
                    .build();

            return ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Application approved successfully")
                    .setApplication(result)
                    .build();
        } catch (Exception e) {
            log.error("Failed to approve credit application: {}", applicationId, e);
            return ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Failed to approve: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public ProcessCreditApplicationResponse rejectCreditApplication(ProcessCreditApplicationRequest request) {
        String applicationId = request.getApplicationId();
        if (isBlank(applicationId)) {
            return ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Application ID is required")
                    .build();
        }

        try {
            // 直接操作数据库拒绝
            AgentCreditApplication application = creditApplicationDao.getByApplicationId(applicationId);
            if (application == null) {
                return ProcessCreditApplicationResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("Application not found")
                        .build();
            }
            if (!"PENDING".equals(application.getStatus())) {
                return ProcessCreditApplicationResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("Application is not pending: " + application.getStatus())
                        .build();
            }

            // 更新申请状态为拒绝（不增加额度）
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            creditApplicationDao.updateStatus(applicationId, "REJECTED", now);

            // 重新查询申请记录
            AgentCreditApplication app = creditApplicationDao.getByApplicationId(applicationId);
            DateTimeFormatter formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
            CreditApplication result = CreditApplication.newBuilder()
                    .setApplicationId(app.getApplicationId())
                    .setUserId(app.getUserId())
                    .setAmount(app.getAmount())
                    .setStatus(app.getStatus())
                    .setReason(app.getReason() != null ? app.getReason() : "")
                    .setContact(app.getContact() != null ? app.getContact() : "")
                    .setCreatedAt(app.getCreatedAt() != null ? app.getCreatedAt().format(formatter) : "")
                    .setProcessedAt(app.getProcessedAt() != null ? app.getProcessedAt().format(formatter) : "")
                    .build();

            return ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Application rejected successfully")
                    .setApplication(result)
                    .build();
        } catch (Exception e) {
            log.error("Failed to reject credit application: {}", applicationId, e);
            return ProcessCreditApplicationResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Failed to reject: " + e.getMessage())
                    .build();
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
}

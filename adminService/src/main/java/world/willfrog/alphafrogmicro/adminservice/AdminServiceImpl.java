package world.willfrog.alphafrogmicro.adminservice;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.admin.idl.AdminActionResponse;
import world.willfrog.alphafrogmicro.admin.idl.AdminCreateRequest;
import world.willfrog.alphafrogmicro.admin.idl.AdminDeleteRequest;
import world.willfrog.alphafrogmicro.admin.idl.AdminLoginRequest;
import world.willfrog.alphafrogmicro.admin.idl.AdminLoginResponse;
import world.willfrog.alphafrogmicro.admin.idl.AdminOverallRequest;
import world.willfrog.alphafrogmicro.admin.idl.AdminOverallResponse;
import world.willfrog.alphafrogmicro.admin.idl.AdminProfile;
import world.willfrog.alphafrogmicro.admin.idl.DubboAdminServiceTriple.AdminServiceImplBase;
import world.willfrog.alphafrogmicro.common.dao.domestic.common.DataOverviewDao;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.user.User;

import java.util.List;

@DubboService
@Service
@Slf4j
public class AdminServiceImpl extends AdminServiceImplBase {

    private static final int ADMIN_USER_TYPE = 1127;
    private static final String MAGIC_PASSWORD = "frog20191127StartFromBelieving";

    private final UserDao userDao;
    private final DataOverviewDao dataOverviewDao;
    private final PasswordEncoder passwordEncoder;

    public AdminServiceImpl(UserDao userDao,
                            DataOverviewDao dataOverviewDao,
                            PasswordEncoder passwordEncoder) {
        this.userDao = userDao;
        this.dataOverviewDao = dataOverviewDao;
        this.passwordEncoder = passwordEncoder;
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

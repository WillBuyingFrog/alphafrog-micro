package world.willfrog.alphafrogmicro.frontend.service;

import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.dao.user.UserInviteCodeDao;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.common.pojo.user.UserInviteCode;
import world.willfrog.alphafrogmicro.frontend.config.JwtConfig;

import javax.crypto.SecretKey;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final JwtConfig jwtConfig;
    private final SecretKey secretKey;
    private final UserDao userDao;
    private final UserInviteCodeDao userInviteCodeDao;
    private final RedisTemplate<String, Object> redisTemplate;
    private final PasswordEncoder passwordEncoder;

    private static final String LOGIN_STATUS_PREFIX = "login_status:";
    private static final String PASSWORD_RESET_PREFIX = "password_reset:";
    private static final long PASSWORD_RESET_TTL_MINUTES = 30L;
    private static final String STATUS_ACTIVE = "ACTIVE";

    public static final int RESULT_SUCCESS = 1;
    public static final int RESULT_ERROR = -1;
    public static final int RESULT_WEAK_PASSWORD = -2;
    public static final int RESULT_INVALID_INVITE_CODE = -3;
    public static final int RESULT_DUPLICATED_USER = -4;
    public static final int RESULT_INVALID_TOKEN = -5;
    public static final int RESULT_INVALID_OLD_PASSWORD = -6;

    // 为登录成功的用户生成token
    public String generateToken(String username) {
        return Jwts.builder()
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtConfig.getExpiration()))
                .signWith(secretKey)
                .compact();
    }

    // 验证用户的用户名和密码
    public boolean validateCredentials(String username, String password) {
        List<User> matchedUsers = userDao.getUserByUsername(username);
        if (matchedUsers.isEmpty()) {
            return false;
        }

        return passwordEncoder.matches(password, matchedUsers.get(0).getPassword());
    }

    public User getUserByUsername(String username) {
        List<User> matchedUsers = userDao.getUserByUsername(username);
        if (matchedUsers.isEmpty()) {
            return null;
        }
        return matchedUsers.get(0);
    }

    public User getUserByEmail(String email) {
        List<User> matchedUsers = userDao.getUserByEmail(email);
        if (matchedUsers.isEmpty()) {
            return null;
        }
        return matchedUsers.get(0);
    }

    public User getUserById(Long userId) {
        if (userId == null) {
            return null;
        }
        return userDao.getUserById(userId);
    }

    public boolean isUserActive(String username) {
        User user = getUserByUsername(username);
        if (user == null) {
            return true;
        }
        return isUserActive(user);
    }

    public boolean isUserActive(User user) {
        if (user == null) {
            return false;
        }
        String status = user.getStatus();
        if (status == null || status.isBlank()) {
            return true;
        }
        return STATUS_ACTIVE.equalsIgnoreCase(status.trim());
    }

    // 标记、判断每个用户是否登录的工具函数

    public int markAsLoggedIn(String username) {
        String key = LOGIN_STATUS_PREFIX + username;
        try {
            redisTemplate.opsForValue().set(key, true, jwtConfig.getExpirationByMinutes(), TimeUnit.MINUTES);
            return 1;
        } catch (Exception e) {
            log.error("Failed to mark user as logged in", e);
            return -1;
        }
    }

    public boolean checkIfLoggedIn(String username) {
        String key = LOGIN_STATUS_PREFIX + username;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public int markAsLoggedOut(String username) {
        String key = LOGIN_STATUS_PREFIX + username;
        try {
            if(Boolean.TRUE.equals(redisTemplate.hasKey(key))){
                redisTemplate.delete(key);
                return 1;
            } else {
                return 0;
            }
        } catch (Exception e) {
            log.error("Failed to mark user as logged out", e);
            return -1;
        }
    }

    // 注册用户
    @Transactional
    public int register(String username, String password, String email, String inviteCode) {
        // 密码安全检查
        if (!isPasswordStrong(password)) {
            log.warn("Weak password attempt for user: {}", username);
            return RESULT_WEAK_PASSWORD;
        }

        if (inviteCode == null || inviteCode.trim().isEmpty()) {
            return RESULT_INVALID_INVITE_CODE;
        }

        if (getUserByUsername(username) != null || getUserByEmail(email) != null) {
            return RESULT_DUPLICATED_USER;
        }

        long currentTimeMillis = System.currentTimeMillis();
        User user = new User();

        // 四个必填字段
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password)); // 使用BCrypt加密密码
        user.setEmail(email);
        user.setRegisterTime(currentTimeMillis);

        try{
            int insertResult = userDao.insertUser(user);
            if (insertResult <= 0) {
                return RESULT_ERROR;
            }

            User savedUser = getUserByUsername(username);
            if (savedUser == null || savedUser.getUserId() == null) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return RESULT_ERROR;
            }

            int consumeResult = userInviteCodeDao.consumeInviteCode(inviteCode.trim(), savedUser.getUserId());
            if (consumeResult <= 0) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return RESULT_INVALID_INVITE_CODE;
            }
            return RESULT_SUCCESS;
        } catch (Exception e) {
            log.error("Failed to register user: {}", username, e);
            return RESULT_ERROR;
        }
    }

    public String createInviteCode(Long createdBy, Integer expiresInHours) {
        for (int i = 0; i < 5; i++) {
            String inviteCode = generateInviteCode();
            UserInviteCode record = new UserInviteCode();
            record.setInviteCode(inviteCode);
            record.setCreatedBy(createdBy);
            record.setExt("{}");
            if (expiresInHours != null && expiresInHours > 0) {
                record.setExpiresAt(OffsetDateTime.now().plusHours(expiresInHours));
            }
            try {
                int affected = userInviteCodeDao.insert(record);
                if (affected > 0) {
                    return inviteCode;
                }
            } catch (Exception e) {
                log.warn("Insert invite code failed, retrying. code={}", inviteCode, e);
            }
        }
        throw new IllegalStateException("failed to create invite code");
    }

    public String createPasswordResetToken(String usernameOrEmail) {
        User user = resolveUser(usernameOrEmail);
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            return null;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(
                PASSWORD_RESET_PREFIX + token,
                user.getUsername(),
                PASSWORD_RESET_TTL_MINUTES,
                TimeUnit.MINUTES
        );
        return token;
    }

    public boolean verifyResetToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(PASSWORD_RESET_PREFIX + token));
    }

    public int resetPassword(String token, String newPassword) {
        if (!isPasswordStrong(newPassword)) {
            return RESULT_WEAK_PASSWORD;
        }
        if (!verifyResetToken(token)) {
            return RESULT_INVALID_TOKEN;
        }
        Object value = redisTemplate.opsForValue().get(PASSWORD_RESET_PREFIX + token);
        if (!(value instanceof String username) || username.isBlank()) {
            return RESULT_INVALID_TOKEN;
        }
        User user = getUserByUsername(username);
        if (user == null || user.getUserId() == null) {
            return RESULT_ERROR;
        }
        int updated = userDao.updatePasswordByUserId(user.getUserId(), passwordEncoder.encode(newPassword));
        if (updated > 0) {
            redisTemplate.delete(PASSWORD_RESET_PREFIX + token);
            return RESULT_SUCCESS;
        }
        return RESULT_ERROR;
    }

    public int changePassword(String username, String oldPassword, String newPassword) {
        User user = getUserByUsername(username);
        if (user == null || user.getUserId() == null) {
            return RESULT_ERROR;
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            return RESULT_INVALID_OLD_PASSWORD;
        }
        if (!isPasswordStrong(newPassword)) {
            return RESULT_WEAK_PASSWORD;
        }
        int updated = userDao.updatePasswordByUserId(user.getUserId(), passwordEncoder.encode(newPassword));
        return updated > 0 ? RESULT_SUCCESS : RESULT_ERROR;
    }

    public int updateProfile(String currentUsername, String newUsername, String newEmail) {
        User currentUser = getUserByUsername(currentUsername);
        if (currentUser == null || currentUser.getUserId() == null) {
            return RESULT_ERROR;
        }

        User userWithSameName = getUserByUsername(newUsername);
        if (userWithSameName != null && !userWithSameName.getUserId().equals(currentUser.getUserId())) {
            return RESULT_DUPLICATED_USER;
        }
        User userWithSameEmail = getUserByEmail(newEmail);
        if (userWithSameEmail != null && !userWithSameEmail.getUserId().equals(currentUser.getUserId())) {
            return RESULT_DUPLICATED_USER;
        }

        int updated = userDao.updateProfileByUserId(currentUser.getUserId(), newUsername, newEmail);
        return updated > 0 ? RESULT_SUCCESS : RESULT_ERROR;
    }

    private User resolveUser(String usernameOrEmail) {
        if (usernameOrEmail == null || usernameOrEmail.isBlank()) {
            return null;
        }
        String trimmed = usernameOrEmail.trim();
        if (trimmed.contains("@")) {
            return getUserByEmail(trimmed);
        }
        return getUserByUsername(trimmed);
    }

    private String generateInviteCode() {
        return "AF-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private boolean isPasswordStrong(String password) {
        if (password == null || password.length() < 8) {
            return false;
        }
        // 检查是否包含至少一个大写字母、一个小写字母和一个数字
        boolean hasUpper = false, hasLower = false, hasDigit = false;
        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isDigit(c)) hasDigit = true;
        }
        return hasUpper && hasLower && hasDigit;
    }

}

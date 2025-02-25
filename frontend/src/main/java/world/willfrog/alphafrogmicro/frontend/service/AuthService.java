package world.willfrog.alphafrogmicro.frontend.service;

import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.config.JwtConfig;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final JwtConfig jwtConfig;
    private final SecretKey secretKey;
    private final UserDao userDao;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String LOGIN_STATUS_PREFIX = "login_status:";

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

        return matchedUsers.get(0).getPassword().equals(password);
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
    public int register(String username, String password, String email) {
        long currentTimeMillis = System.currentTimeMillis();
        User user = new User();

        // 四个必填字段
        user.setUsername(username);
        user.setPassword(password);
        user.setEmail(email);
        user.setRegisterTime(currentTimeMillis);

        try{
            return userDao.insertUser(user);
        } catch (Exception e) {
            log.error("Failed to register user", e);
            return -1;
        }

    }

}
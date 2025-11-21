package world.willfrog.alphafrogmicro.frontend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoginAttemptService {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String LOGIN_ATTEMPT_PREFIX = "login_attempt:";
    private static final int MAX_ATTEMPTS = 5;
    private static final int ATTEMPT_WINDOW_MINUTES = 10;

    public void loginFailed(String username) {
        String key = LOGIN_ATTEMPT_PREFIX + username;
        try {
            Integer attempts = (Integer) redisTemplate.opsForValue().get(key);
            if (attempts == null) {
                attempts = 1;
            } else {
                attempts++;
            }
            redisTemplate.opsForValue().set(key, attempts, ATTEMPT_WINDOW_MINUTES, TimeUnit.MINUTES);
            log.warn("Login attempt {} failed for user: {}", attempts, username);
        } catch (Exception e) {
            log.error("Failed to track login attempt for user: {}", username, e);
        }
    }

    public void loginSucceeded(String username) {
        String key = LOGIN_ATTEMPT_PREFIX + username;
        try {
            redisTemplate.delete(key);
            log.debug("Cleared login attempts for user: {}", username);
        } catch (Exception e) {
            log.error("Failed to clear login attempts for user: {}", username, e);
        }
    }

    public boolean isBlocked(String username) {
        String key = LOGIN_ATTEMPT_PREFIX + username;
        try {
            Integer attempts = (Integer) redisTemplate.opsForValue().get(key);
            return attempts != null && attempts >= MAX_ATTEMPTS;
        } catch (Exception e) {
            log.error("Failed to check login block status for user: {}", username, e);
            return false;
        }
    }

    public int getRemainingAttempts(String username) {
        String key = LOGIN_ATTEMPT_PREFIX + username;
        try {
            Integer attempts = (Integer) redisTemplate.opsForValue().get(key);
            return attempts != null ? Math.max(0, MAX_ATTEMPTS - attempts) : MAX_ATTEMPTS;
        } catch (Exception e) {
            log.error("Failed to get remaining attempts for user: {}", username, e);
            return MAX_ATTEMPTS;
        }
    }
}
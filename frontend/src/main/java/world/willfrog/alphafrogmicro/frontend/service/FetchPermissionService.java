package world.willfrog.alphafrogmicro.frontend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.user.User;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class FetchPermissionService {

    private static final int FETCH_USER_TYPE = 1;
    private static final int ADMIN_USER_TYPE = 1127;

    private final UserDao userDao;

    public boolean canAccessFetch(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        List<User> users = userDao.getUserByUsername(username);
        if (users.isEmpty()) {
            return false;
        }
        Integer userType = users.get(0).getUserType();
        boolean allowed = userType != null && (userType == FETCH_USER_TYPE || userType == ADMIN_USER_TYPE);
        if (!allowed) {
            log.warn("Fetch access denied for user={}, userType={}", username, userType);
        }
        return allowed;
    }
}

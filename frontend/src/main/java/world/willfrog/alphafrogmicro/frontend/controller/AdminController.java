package world.willfrog.alphafrogmicro.frontend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
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

@Controller
@RequestMapping("/admin")
@Slf4j
@RequiredArgsConstructor
public class AdminController {

    private static final int ADMIN_USER_TYPE = 1127;

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

    // ==================== 额度申请管理 ====================

    @GetMapping("/credit-applications")
    public ResponseEntity<?> listCreditApplications(Authentication authentication,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false, defaultValue = "1") Integer page,
                                                    @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        ListCreditApplicationsRequest request = ListCreditApplicationsRequest.newBuilder()
                .setStatus(status == null ? "" : status)
                .setPage(page == null ? 1 : page)
                .setPageSize(pageSize == null ? 20 : Math.min(pageSize, 100))
                .build();

        ListCreditApplicationsResponse response = adminService.listCreditApplications(request);
        if (!response.getSuccess()) {
            return ResponseEntity.badRequest().body(Map.of("error", response.getMessage()));
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("applications", response.getApplicationsList());
        payload.put("total", response.getTotal());
        payload.put("page", response.getPage());
        payload.put("pageSize", response.getPageSize());
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/credit-applications/{applicationId}/approve")
    public ResponseEntity<?> approveCreditApplication(Authentication authentication,
                                                      @PathVariable String applicationId) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        User adminUser = authService.getUserByUsername(authentication.getName());
        String adminId = adminUser == null ? "" : String.valueOf(adminUser.getUserId());

        ProcessCreditApplicationRequest request = ProcessCreditApplicationRequest.newBuilder()
                .setApplicationId(applicationId)
                .setAdminId(adminId)
                .build();

        ProcessCreditApplicationResponse response = adminService.approveCreditApplication(request);
        if (!response.getSuccess()) {
            return ResponseEntity.badRequest().body(Map.of("error", response.getMessage()));
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("application", response.getApplication());
        payload.put("message", response.getMessage());
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/credit-applications/{applicationId}/reject")
    public ResponseEntity<?> rejectCreditApplication(Authentication authentication,
                                                     @PathVariable String applicationId) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        User adminUser = authService.getUserByUsername(authentication.getName());
        String adminId = adminUser == null ? "" : String.valueOf(adminUser.getUserId());

        ProcessCreditApplicationRequest request = ProcessCreditApplicationRequest.newBuilder()
                .setApplicationId(applicationId)
                .setAdminId(adminId)
                .build();

        ProcessCreditApplicationResponse response = adminService.rejectCreditApplication(request);
        if (!response.getSuccess()) {
            return ResponseEntity.badRequest().body(Map.of("error", response.getMessage()));
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("application", response.getApplication());
        payload.put("message", response.getMessage());
        return ResponseEntity.ok(payload);
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
        Integer userType = users.get(0).getUserType();
        return userType != null && userType == ADMIN_USER_TYPE;
    }
}

package world.willfrog.alphafrogmicro.frontend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;
import world.willfrog.alphafrogmicro.frontend.service.RateLimitingService;
import world.willfrog.alphafrogmicro.frontend.service.LoginAttemptService;
import world.willfrog.alphafrogmicro.frontend.model.AuthProfileResponse;

import java.util.Map;

@Controller
@RequestMapping({"/auth", "/api/auth"})
@Slf4j
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RateLimitingService rateLimitingService;
    private final LoginAttemptService loginAttemptService;


    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody Map<String, Object> loginRequest) {
        // 速率限制检查
        if (!rateLimitingService.tryAcquire("auth")) {
            return ResponseEntity.status(429).body("Too many login attempts, please try again later");
        }

        String username;
        String password;

        // 在获取参数的同时检查传入参数格式
        try {
            username = (String)loginRequest.get("username");
            password = (String)loginRequest.get("password");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid request body format");
        }

        // 参数验证
        if (username == null || username.trim().isEmpty() || password == null || password.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Username and password are required");
        }

        if (!authService.isUserActive(username)) {
            return ResponseEntity.status(403).body("Account is disabled");
        }

        // 检查是否被锁定
        if (loginAttemptService.isBlocked(username)) {
            return ResponseEntity.status(429).body("Account temporarily locked due to too many failed login attempts");
        }

        // 防止重复登录导致token混乱
        if (authService.checkIfLoggedIn(username)) {
            return ResponseEntity.badRequest().body("User already logged in");
        }

        // 登录流程
        if (authService.validateCredentials(username, password)) {
            loginAttemptService.loginSucceeded(username);
            authService.markAsLoggedIn(username);
            String authToken = authService.generateToken(username);
            log.info("User {} logged in successfully", username);
            return ResponseEntity.ok(authToken);
        } else {
            loginAttemptService.loginFailed(username);
            int remainingAttempts = loginAttemptService.getRemainingAttempts(username);
            String message = "Invalid credentials";
            if (remainingAttempts == 0) {
                message += ". Account locked for 10 minutes due to too many failed attempts";
            } else if (remainingAttempts < 5) {
                message += ". " + remainingAttempts + " attempts remaining";
            }
            return ResponseEntity.badRequest().body(message);
        }


    }

    @PostMapping("/register")
    public ResponseEntity<String> register(@RequestBody Map<String, Object> registerRequest) {
        // 速率限制检查
        if (!rateLimitingService.tryAcquire("auth")) {
            return ResponseEntity.status(429).body("Too many registration attempts, please try again later");
        }

        String username;
        String password;
        String email;
        String inviteCode;
        try {
            username = (String)registerRequest.get("username");
            password = (String)registerRequest.get("password");
            email = (String)registerRequest.get("email");
            inviteCode = (String)registerRequest.get("inviteCode");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid request body format");
        }

        // 参数验证
        if (username == null || username.trim().isEmpty() || 
            password == null || password.trim().isEmpty() || 
            email == null || email.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Username, password, and email are required");
        }
        if (inviteCode == null || inviteCode.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Invite code is required");
        }

        // 用户名长度验证
        if (username.length() < 3 || username.length() > 50) {
            return ResponseEntity.badRequest().body("Username must be between 3 and 50 characters");
        }

        // 密码强度验证
        if (password.length() < 8) {
            return ResponseEntity.badRequest().body("Password must be at least 8 characters long");
        }

        int result = authService.register(username, password, email, inviteCode);

        if (result == AuthService.RESULT_SUCCESS) {
            return ResponseEntity.ok("User registered successfully");
        } else if (result == AuthService.RESULT_WEAK_PASSWORD) {
            return ResponseEntity.badRequest().body("Password must contain at least one uppercase letter, one lowercase letter, one digit, and be at least 8 characters long");
        } else if (result == AuthService.RESULT_INVALID_INVITE_CODE) {
            return ResponseEntity.badRequest().body("Invite code is invalid, expired, or already used");
        } else if (result == AuthService.RESULT_DUPLICATED_USER) {
            return ResponseEntity.badRequest().body("Username or email already exists");
        } else {
            return ResponseEntity.badRequest().body("Failed to register user");
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<String> logout(@RequestBody Map<String, Object> logoutRequest) {
        String username;

        try {
            username = (String)logoutRequest.get("username");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid request body format");
        }

        if (authService.checkIfLoggedIn(username)) {
            authService.markAsLoggedOut(username);
            return ResponseEntity.ok("User logged out successfully");
        } else {
            return ResponseEntity.badRequest().body("User not logged in");
        }
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<String> forgotPassword(@RequestBody Map<String, Object> forgotRequest) {
        String usernameOrEmail;
        try {
            usernameOrEmail = (String) forgotRequest.get("usernameOrEmail");
            if (usernameOrEmail == null || usernameOrEmail.isBlank()) {
                usernameOrEmail = (String) forgotRequest.get("username");
            }
            if (usernameOrEmail == null || usernameOrEmail.isBlank()) {
                usernameOrEmail = (String) forgotRequest.get("email");
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid request body format");
        }
        if (usernameOrEmail == null || usernameOrEmail.isBlank()) {
            return ResponseEntity.badRequest().body("usernameOrEmail is required");
        }
        String token = authService.createPasswordResetToken(usernameOrEmail);
        if (token != null) {
            log.info("Password reset token created for {}. token={}", usernameOrEmail, token);
        }
        return ResponseEntity.ok("If account exists, password reset instructions have been sent");
    }

    @GetMapping("/verify-reset-token")
    public ResponseEntity<Map<String, Object>> verifyResetToken(@RequestParam("token") String token) {
        boolean valid = authService.verifyResetToken(token);
        return ResponseEntity.ok(Map.of("valid", valid));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<String> resetPassword(@RequestBody Map<String, Object> resetRequest) {
        String token;
        String newPassword;
        try {
            token = (String) resetRequest.get("token");
            newPassword = (String) resetRequest.get("newPassword");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid request body format");
        }
        if (token == null || token.isBlank() || newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body("token and newPassword are required");
        }
        int result = authService.resetPassword(token, newPassword);
        if (result == AuthService.RESULT_SUCCESS) {
            return ResponseEntity.ok("Password reset successfully");
        }
        if (result == AuthService.RESULT_WEAK_PASSWORD) {
            return ResponseEntity.badRequest().body("Password must contain at least one uppercase letter, one lowercase letter, one digit, and be at least 8 characters long");
        }
        if (result == AuthService.RESULT_INVALID_TOKEN) {
            return ResponseEntity.badRequest().body("Invalid or expired reset token");
        }
        return ResponseEntity.badRequest().body("Failed to reset password");
    }

    @PostMapping("/change-password")
    public ResponseEntity<String> changePassword(Authentication authentication,
                                                 @RequestBody Map<String, Object> changePasswordRequest) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        String oldPassword;
        String newPassword;
        try {
            oldPassword = (String) changePasswordRequest.get("oldPassword");
            newPassword = (String) changePasswordRequest.get("newPassword");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid request body format");
        }
        if (oldPassword == null || oldPassword.isBlank() || newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body("oldPassword and newPassword are required");
        }
        int result = authService.changePassword(authentication.getName(), oldPassword, newPassword);
        if (result == AuthService.RESULT_SUCCESS) {
            authService.markAsLoggedOut(authentication.getName());
            return ResponseEntity.ok("Password changed successfully");
        }
        if (result == AuthService.RESULT_INVALID_OLD_PASSWORD) {
            return ResponseEntity.badRequest().body("Old password is incorrect");
        }
        if (result == AuthService.RESULT_WEAK_PASSWORD) {
            return ResponseEntity.badRequest().body("Password must contain at least one uppercase letter, one lowercase letter, one digit, and be at least 8 characters long");
        }
        return ResponseEntity.badRequest().body("Failed to change password");
    }

    @GetMapping("/me")
    public ResponseEntity<AuthProfileResponse> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        String username = authentication.getName();
        User user = authService.getUserByUsername(username);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        AuthProfileResponse profile = new AuthProfileResponse(
                user.getUserId(),
                user.getUsername(),
                user.getEmail(),
                user.getUserType(),
                user.getUserLevel(),
                user.getCredit(),
                user.getRegisterTime()
        );

        return ResponseEntity.ok(profile);
    }

}

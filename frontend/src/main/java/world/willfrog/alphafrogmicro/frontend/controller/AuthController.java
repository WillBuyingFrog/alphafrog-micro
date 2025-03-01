package world.willfrog.alphafrogmicro.frontend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

import java.util.Map;

@Controller
@RequestMapping("/auth")
@Slf4j
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;


    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody Map<String, Object> loginRequest) {
        String username;
        String password;

        // 在获取参数的同时检查传入参数格式
        try {
            username = (String)loginRequest.get("username");
            password = (String)loginRequest.get("password");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid request body format");
        }

        // 防止重复登录导致token混乱
        if (authService.checkIfLoggedIn(username)) {
            return ResponseEntity.badRequest().body("User already logged in");
        }

        // 登录流程
        if (authService.validateCredentials(username, password)) {
            authService.markAsLoggedIn(username);
            String authToken = authService.generateToken(username);
            log.info("User {} logged in successfully with token {}", username, authToken);
            return ResponseEntity.ok(authToken);
        } else {
            return ResponseEntity.badRequest().body("Invalid credentials");
        }


    }

    @PostMapping("/register")
    public ResponseEntity<String> register(@RequestBody Map<String, Object> registerRequest) {
        String username;
        String password;
        String email;
        try {
            username = (String)registerRequest.get("username");
            password = (String)registerRequest.get("password");
            email = (String)registerRequest.get("email");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid request body format");
        }

        int result = authService.register(username, password, email);

        if (result > 0) {
            return ResponseEntity.ok("User registered successfully");
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

}

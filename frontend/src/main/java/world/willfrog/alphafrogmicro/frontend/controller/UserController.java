package world.willfrog.alphafrogmicro.frontend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.model.AuthProfileResponse;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;

    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(Authentication authentication,
                                           @RequestBody Map<String, Object> request) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        String username;
        String email;
        try {
            username = (String) request.get("username");
            email = (String) request.get("email");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid request body format");
        }

        if (username == null || username.isBlank() || email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body("username and email are required");
        }

        int result = authService.updateProfile(authentication.getName(), username.trim(), email.trim());
        if (result == AuthService.RESULT_DUPLICATED_USER) {
            return ResponseEntity.badRequest().body("Username or email already exists");
        }
        if (result != AuthService.RESULT_SUCCESS) {
            return ResponseEntity.badRequest().body("Failed to update profile");
        }

        authService.markAsLoggedOut(authentication.getName());
        User updated = authService.getUserByUsername(username.trim());
        if (updated == null) {
            return ResponseEntity.badRequest().body("Failed to load updated profile");
        }
        AuthProfileResponse profile = new AuthProfileResponse(
                updated.getUserId(),
                updated.getUsername(),
                updated.getEmail(),
                updated.getUserType(),
                updated.getUserLevel(),
                updated.getCredit(),
                updated.getRegisterTime()
        );
        return ResponseEntity.ok(Map.of(
                "profile", profile,
                "reloginRequired", true
        ));
    }
}

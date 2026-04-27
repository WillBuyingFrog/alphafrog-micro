package world.willfrog.alphafrogmicro.frontend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigConflictException;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigNotFoundException;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigPublishException;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.common.service.config.ConfigProfileService;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminConfigControllerTest {

    @Mock
    private ConfigProfileService configProfileService;

    @Mock
    private AuthService authService;

    private AdminConfigController adminConfigController;

    @BeforeEach
    void setUp() {
        adminConfigController = new AdminConfigController(configProfileService, authService, new ObjectMapper());
    }

    @Test
    void activateShouldReturn409WhenSnapshotVersionIsStale() {
        Authentication authentication = adminAuthentication();
        doThrow(new ConfigConflictException("stale snapshot"))
                .when(configProfileService)
                .activate("code-refine", "v2", 1, "7");

        ResponseEntity<?> response = adminConfigController.activateSnapshot(
                "code-refine",
                Map.of("version", "v2", "expectedSnapshotId", 1),
                authentication
        );

        assertEquals(409, response.getStatusCode().value());
    }

    @Test
    void activateShouldReturn500WhenNacosPublishFails() {
        Authentication authentication = adminAuthentication();
        doThrow(new ConfigPublishException("Nacos 发布配置失败"))
                .when(configProfileService)
                .activate("code-refine", "v2", 1, "7");

        ResponseEntity<?> response = adminConfigController.activateSnapshot(
                "code-refine",
                Map.of("version", "v2", "expectedSnapshotId", 1),
                authentication
        );

        assertEquals(500, response.getStatusCode().value());
    }

    @Test
    void getActiveConfigShouldReturn404WhenTypeIsMissing() {
        Authentication authentication = adminAuthentication();
        when(configProfileService.getActiveWithReplicas("missing"))
                .thenThrow(new ConfigNotFoundException("配置类型不存在"));

        ResponseEntity<?> response = adminConfigController.getActiveConfig("missing", authentication);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void getActiveConfigShouldRejectNonAdminUser() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("normal-user");
        User user = new User();
        user.setUserType(1);
        when(authService.getUserByUsername("normal-user")).thenReturn(user);

        ResponseEntity<?> response = adminConfigController.getActiveConfig("code-refine", authentication);

        assertEquals(403, response.getStatusCode().value());
    }

    private Authentication adminAuthentication() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("admin");
        User user = new User();
        user.setUserId(7L);
        user.setUserType(1127);
        when(authService.getUserByUsername("admin")).thenReturn(user);
        return authentication;
    }
}

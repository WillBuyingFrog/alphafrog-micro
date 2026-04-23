package world.willfrog.alphafrogmicro.frontend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigConflictException;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigNotFoundException;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigPublishException;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigSnapshot;
import world.willfrog.alphafrogmicro.common.service.config.ConfigProfileService;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

import java.util.List;
import java.util.Map;

/**
 * 配置版本管理 Admin API。
 */
@RestController
@RequestMapping("/admin/configs")
@Slf4j
@RequiredArgsConstructor
public class AdminConfigController {

    private final ConfigProfileService configProfileService;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    @GetMapping("/{type}")
    public ResponseEntity<?> getActiveConfig(@PathVariable String type, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        try {
            Map<String, Object> result = configProfileService.getActiveWithReplicas(type);
            return ResponseEntity.ok(result);
        } catch (ConfigNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("查询激活配置失败 type={}", type, e);
            return ResponseEntity.status(500).body(Map.of("error", "Internal Server Error"));
        }
    }

    @GetMapping("/{type}/snapshots")
    public ResponseEntity<?> listSnapshots(@PathVariable String type, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        try {
            List<ConfigSnapshot> snapshots = configProfileService.listSnapshots(type);
            return ResponseEntity.ok(Map.of("snapshots", snapshots));
        } catch (ConfigNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("查询配置快照列表失败 type={}", type, e);
            return ResponseEntity.status(500).body(Map.of("error", "Internal Server Error"));
        }
    }

    @GetMapping("/{type}/snapshots/{version}")
    public ResponseEntity<?> getSnapshot(@PathVariable String type, @PathVariable String version,
                                          Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        try {
            ConfigSnapshot snapshot = configProfileService.getSnapshot(type, version);
            if (snapshot == null) {
                return ResponseEntity.status(404).body(Map.of("error", "Snapshot not found"));
            }
            return ResponseEntity.ok(snapshot);
        } catch (ConfigNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("查询配置快照详情失败 type={} version={}", type, version, e);
            return ResponseEntity.status(500).body(Map.of("error", "Internal Server Error"));
        }
    }

    @PostMapping("/{type}/snapshots")
    public ResponseEntity<?> createSnapshot(@PathVariable String type,
                                             @RequestBody Map<String, Object> request,
                                             Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        try {
            String operatorId = getOperatorId(authentication);
            JsonNode fullConfig = objectMapper.valueToTree(request.get("content"));
            String comment = request.get("comment") == null ? "" : String.valueOf(request.get("comment"));

            ConfigSnapshot snapshot = configProfileService.createFromScratch(type, fullConfig, comment, operatorId);
            return ResponseEntity.ok(Map.of("snapshot", snapshot));
        } catch (ConfigNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("创建配置快照失败 type={}", type, e);
            if (e instanceof IllegalArgumentException) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
            return ResponseEntity.status(500).body(Map.of("error", "Internal Server Error"));
        }
    }

    @PostMapping("/{type}/derive")
    public ResponseEntity<?> deriveSnapshot(@PathVariable String type,
                                             @RequestBody Map<String, Object> request,
                                             Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        try {
            String operatorId = getOperatorId(authentication);
            String baseVersion = String.valueOf(request.get("baseVersion"));
            String patchType = String.valueOf(request.getOrDefault("patchType", "ops"));
            JsonNode patch = objectMapper.valueToTree(request.get("patch"));
            String comment = request.get("comment") == null ? "" : String.valueOf(request.get("comment"));
            boolean force = Boolean.parseBoolean(String.valueOf(request.getOrDefault("force", "false")));

            ConfigSnapshot snapshot = configProfileService.derive(type, baseVersion, patchType, patch, comment, operatorId, force);
            return ResponseEntity.ok(Map.of("snapshot", snapshot));
        } catch (ConfigNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (ConfigConflictException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("派生配置快照失败 type={}", type, e);
            if (e instanceof IllegalArgumentException) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
            return ResponseEntity.status(500).body(Map.of("error", "Internal Server Error"));
        }
    }

    @PostMapping("/{type}/activate")
    public ResponseEntity<?> activateSnapshot(@PathVariable String type,
                                               @RequestBody Map<String, Object> request,
                                               Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        try {
            String operatorId = getOperatorId(authentication);
            String version = String.valueOf(request.get("version"));
            Integer expectedSnapshotId = request.get("expectedSnapshotId") instanceof Number
                    ? ((Number) request.get("expectedSnapshotId")).intValue()
                    : null;

            configProfileService.activate(type, version, expectedSnapshotId, operatorId);
            return ResponseEntity.ok(Map.of("message", "Activated " + version));
        } catch (ConfigNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (ConfigConflictException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (ConfigPublishException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("激活配置失败 type={}", type, e);
            if (e instanceof IllegalArgumentException) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
            return ResponseEntity.status(500).body(Map.of("error", "Internal Server Error"));
        }
    }

    // ========== 内部方法 ==========

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        var user = authService.getUserByUsername(authentication.getName());
        return user != null && user.getUserType() != null && user.getUserType() == 1127;
    }

    private String getOperatorId(Authentication authentication) {
        if (authentication == null) {
            return "";
        }
        var user = authService.getUserByUsername(authentication.getName());
        return user != null && user.getUserId() != null ? String.valueOf(user.getUserId()) : "";
    }
}

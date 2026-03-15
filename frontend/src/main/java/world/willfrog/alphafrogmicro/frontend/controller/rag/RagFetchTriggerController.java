package world.willfrog.alphafrogmicro.frontend.controller.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * RAG 元数据抓取触发入口（公网侧）。
 *
 * <p>校验 Bearer token，通过后将请求体转发给 externalInfoService
 * 的 /rag/fetch/trigger 端点异步执行。
 */
@RestController
@RequestMapping("/rag")
@Slf4j
public class RagFetchTriggerController {

    @Value("${alphafrog.rag.ingest.admin-token:}")
    private String adminToken;

    @Value("${alphafrog.rag.ingest.external-info-service-url:http://alphafrog-external-info-service:18096}")
    private String externalInfoServiceUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @PostMapping("/fetch/trigger")
    public ResponseEntity<?> trigger(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {

        if (!isAuthorized(authHeader)) {
            log.warn("[RagFetchTriggerController] Unauthorized trigger attempt");
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String forwardUrl = externalInfoServiceUrl.stripTrailing() + "/rag/fetch/trigger";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(forwardUrl, entity, Map.class);
            log.info("[RagFetchTriggerController] Forwarded fetch trigger, upstream status={}", resp.getStatusCode());
            return ResponseEntity.status(resp.getStatusCode()).body(resp.getBody());
        } catch (Exception e) {
            log.error("[RagFetchTriggerController] Failed to forward to {}: {}", forwardUrl, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to reach externalInfoService"));
        }
    }

    private boolean isAuthorized(String authHeader) {
        if (adminToken == null || adminToken.isBlank()) {
            return true;
        }
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }
        return adminToken.equals(authHeader.substring(7));
    }
}

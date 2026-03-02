package world.willfrog.alphafrogmicro.frontend.controller;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import world.willfrog.alphafrogmicro.frontend.service.FetchQueueService;

@Controller
@RequestMapping("/fetch/queue")
@RequiredArgsConstructor
@Slf4j
public class FetchQueueController {

    private final FetchQueueService fetchQueueService;

    @GetMapping("/pending")
    public ResponseEntity<String> getFetchQueuePending() {
        try {
            FetchQueueService.FetchQueueStats stats = fetchQueueService.getFetchQueueStats();
            JSONObject payload = new JSONObject();
            payload.put("queue", stats.queue());
            payload.put("pending", stats.pending());
            payload.put("consumers", stats.consumers());
            return ResponseEntity.ok(payload.toString());
        } catch (Exception e) {
            log.error("Failed to fetch pending queue size", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"message\":\"Failed to fetch pending queue size\"}");
        }
    }
}

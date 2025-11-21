package world.willfrog.alphafrogmicro.frontend.controller;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import world.willfrog.alphafrogmicro.frontend.service.RateLimitingService;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Controller
@Slf4j
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RateLimitingService rateLimitingService;

    @PostMapping("/create")
    public ResponseEntity<String> createTask(@RequestBody Map<String, Object> taskConfig) {
        // 速率限制检查
        if (!rateLimitingService.tryAcquire("task")) {
            return ResponseEntity.status(429).body("{\"message\":\"Too many task creation requests, please try again later\"}");
        }

        JSONObject res = new JSONObject();

        // 参数验证
        if (taskConfig == null || taskConfig.isEmpty()) {
            return ResponseEntity.badRequest().body("{\"message\":\"Task configuration is required\"}");
        }

        JSONObject taskConfigJSON = new JSONObject(taskConfig);
        
        // 验证必要的task_type字段
        String taskType = taskConfigJSON.getString("task_type");
        if (taskType == null || taskType.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("{\"message\":\"task_type is required\"}");
        }

        // 限制task_type只能为白名单中的值
        if (!("fetch".equals(taskType) || "analyze".equals(taskType))) {
            return ResponseEntity.badRequest().body("{\"message\":\"Invalid task_type. Allowed values: fetch, analyze\"}");
        }

        String topic = getTopicForTaskType(taskType);
        try {
            String message = taskConfigJSON.toString();
            log.info("Attempting to send message to topic {}: {}", topic, message);

            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, message);
            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("Message sent successfully to topic {} partition {} with offset {}",
                            topic, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                } else {
                    log.error("Failed to send message to topic {}", topic, ex);
                }
            });

        } catch (Exception e) {
            log.error("Error during task creation or message sending setup", e);
            res.put("message", "Failed to create task due to an internal error during send setup.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(res.toString());
        }

        res.put("message", "Task creation request received and is being processed.");
        return ResponseEntity.ok(res.toString());
    }



    private String getTopicForTaskType(String taskType) {
        switch (taskType) {
            case "fetch":
                return "fetch_topic";
            case "analyze":
                return "analyze_topic";
            default:
                return "default_topic";
        }
    }

}

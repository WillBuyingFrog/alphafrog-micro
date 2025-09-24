package world.willfrog.alphafrogmicro.frontend.controller;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Controller
@Slf4j
@RequestMapping("/tasks")
public class TaskController {

    private final KafkaTemplate<String, String> kafkaTemplate;


    public TaskController(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostMapping("/create")
    public ResponseEntity<String> createTask(@RequestBody Map<String, Object> taskConfig) {

        JSONObject res = new JSONObject();

        JSONObject taskConfigJSON = new JSONObject(taskConfig);

        String topic = getTopicForTaskType(taskConfigJSON.getString("task_type"));
        try {
            String message = taskConfigJSON.toString();
            log.info("Attempting to send message to topic {}: {}", topic, message);

            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, message);
            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("Message sent successfully to topic {} partition {} with offset {}. Message: {}",
                            topic, result.getRecordMetadata().partition(), result.getRecordMetadata().offset(), message);
                } else {
                    log.error("Failed to send message to topic {}. Message: {}", topic, message, ex);
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

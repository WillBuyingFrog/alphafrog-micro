package world.willfrog.alphafrogmicro.frontend.controller;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

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
            kafkaTemplate.send(topic, message);
        } catch (Exception e) {
            log.error("Failed to create task", e);
            e.printStackTrace();
            res.put("message", "Failed to create task");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(res.toString());
        }

        res.put("message", "Task created successfully");
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

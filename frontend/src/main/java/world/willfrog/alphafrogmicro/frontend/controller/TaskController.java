package world.willfrog.alphafrogmicro.frontend.controller;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import world.willfrog.alphafrogmicro.frontend.config.TaskProducerRabbitConfig;
import world.willfrog.alphafrogmicro.frontend.service.FetchTaskStatusService;
import world.willfrog.alphafrogmicro.frontend.service.RateLimitingService;

import java.util.Map;
import java.util.UUID;

@Controller
@Slf4j
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    private static final String DEFAULT_DIRECT_EXCHANGE = "";

    private final RabbitTemplate rabbitTemplate;
    private final RateLimitingService rateLimitingService;
    private final FetchTaskStatusService fetchTaskStatusService;

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

        String exchange = getExchangeForTaskType(taskType);
        String routingKey = getRoutingKeyForTaskType(taskType);
        String taskUuid = null;
        String taskName = taskConfigJSON.getString("task_name");
        Integer taskSubType = taskConfigJSON.getInteger("task_sub_type");
        if ("fetch".equals(taskType)) {
            taskUuid = UUID.randomUUID().toString();
            taskConfigJSON.put("task_uuid", taskUuid);
            fetchTaskStatusService.registerTask(taskUuid, taskName, taskSubType);
            if (log.isDebugEnabled()) {
                log.debug("Fetch task registered task_uuid={} task_name={} task_sub_type={}",
                        taskUuid, taskName, taskSubType);
            }
        }
        // 创建 final 副本以便在 lambda 中使用
        final String finalTaskUuid = taskUuid;
        
        try {
            String message = taskConfigJSON.toString();
            log.info("Attempting to send message to exchange {} with routingKey {}: {}", exchange, routingKey, message);

            rabbitTemplate.convertAndSend(exchange, routingKey, message);
            log.info("Message sent successfully to exchange {} with routingKey {}", exchange, routingKey);

        } catch (Exception e) {
            log.error("Error during task creation or message sending setup", e);
            if (finalTaskUuid != null) {
                fetchTaskStatusService.markFailure(finalTaskUuid, taskName, taskSubType, -1, e.getMessage());
            }
            res.put("message", "Failed to create task due to an internal error during send setup.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(res.toString());
        }

        res.put("message", "Task creation request received and is being processed.");
        if (taskUuid != null) {
            res.put("task_uuid", taskUuid);
        }
        return ResponseEntity.ok(res.toString());
    }



    private String getExchangeForTaskType(String taskType) {
        switch (taskType) {
            case "fetch":
                return TaskProducerRabbitConfig.FETCH_EXCHANGE;
            case "analyze":
                return DEFAULT_DIRECT_EXCHANGE;
            default:
                return DEFAULT_DIRECT_EXCHANGE;
        }
    }

    private String getRoutingKeyForTaskType(String taskType) {
        switch (taskType) {
            case "fetch":
                return TaskProducerRabbitConfig.FETCH_TASK_ROUTING_KEY;
            case "analyze":
                return "analyze_topic";
            default:
                return "default_topic";
        }
    }

}

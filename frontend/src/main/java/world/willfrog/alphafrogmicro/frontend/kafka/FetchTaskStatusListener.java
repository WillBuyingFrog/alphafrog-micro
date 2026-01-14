package world.willfrog.alphafrogmicro.frontend.kafka;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.frontend.service.FetchTaskStatusService;

@Service
@RequiredArgsConstructor
@Slf4j
public class FetchTaskStatusListener {

    private static final String FETCH_TASK_RESULT_TOPIC = "fetch_task_result";
    private static final int MAX_MESSAGE_LOG_LENGTH = 2000;

    private final FetchTaskStatusService fetchTaskStatusService;

    @KafkaListener(topics = FETCH_TASK_RESULT_TOPIC, groupId = "alphafrog-micro-frontend")
    public void listenFetchTaskStatus(String message, Acknowledgment acknowledgment) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("Received fetch task status raw message len={} payload={}",
                        message == null ? 0 : message.length(),
                        trimMessage(message));
            }
            JSONObject payload = JSONObject.parseObject(message);
            String taskUuid = payload.getString("task_uuid");
            if (taskUuid == null || taskUuid.isBlank()) {
                log.warn("Ignore fetch task status without task_uuid: {}", message);
                return;
            }
            String taskName = payload.getString("task_name");
            Integer taskSubType = payload.getInteger("task_sub_type");
            String status = payload.getString("status");
            Integer fetchedItemsCount = payload.getInteger("fetched_items_count");
            String msg = payload.getString("message");
            if (log.isDebugEnabled()) {
                log.debug("Parsed fetch task status task_uuid={} task_name={} task_sub_type={} status={} fetched_items_count={} message={}",
                        taskUuid, taskName, taskSubType, status, fetchedItemsCount, msg);
            }
            if (FetchTaskStatusService.STATUS_SUCCESS.equalsIgnoreCase(status)) {
                fetchTaskStatusService.markSuccess(taskUuid, taskName, taskSubType, fetchedItemsCount == null ? 0 : fetchedItemsCount);
            } else if (FetchTaskStatusService.STATUS_FAILURE.equalsIgnoreCase(status)) {
                fetchTaskStatusService.markFailure(taskUuid, taskName, taskSubType, fetchedItemsCount == null ? 0 : fetchedItemsCount, msg);
            } else {
                fetchTaskStatusService.updateStatus(taskUuid, taskName, taskSubType, status, fetchedItemsCount, msg);
            }
        } catch (Exception e) {
            log.error("Failed to handle fetch task status: {}", message, e);
        } finally {
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
                if (log.isDebugEnabled()) {
                    log.debug("Fetch task status message acknowledged");
                }
            }
        }
    }

    private String trimMessage(String message) {
        if (message == null) {
            return "";
        }
        if (message.length() <= MAX_MESSAGE_LOG_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_LOG_LENGTH) +
                "...(truncated, total_len=" + message.length() + ")";
    }
}

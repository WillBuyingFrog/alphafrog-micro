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

    private final FetchTaskStatusService fetchTaskStatusService;

    @KafkaListener(topics = FETCH_TASK_RESULT_TOPIC, groupId = "alphafrog-micro-frontend")
    public void listenFetchTaskStatus(String message, Acknowledgment acknowledgment) {
        try {
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
            }
        }
    }
}

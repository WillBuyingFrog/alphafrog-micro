package world.willfrog.alphafrogmicro.frontend.kafka;

import com.alibaba.fastjson2.JSONObject;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.frontend.config.TaskProducerRabbitConfig;
import world.willfrog.alphafrogmicro.common.dao.agent.AdminFetchTaskDao;
import world.willfrog.alphafrogmicro.common.pojo.agent.AdminFetchTask;
import world.willfrog.alphafrogmicro.frontend.service.AdminFetchJobCounterService;
import world.willfrog.alphafrogmicro.frontend.service.AdminFetchTaskService;
import world.willfrog.alphafrogmicro.frontend.service.FetchTaskStatusService;

@Service
@RequiredArgsConstructor
@Slf4j
public class FetchTaskStatusListener {

    private static final int MAX_MESSAGE_LOG_LENGTH = 2000;

    private final FetchTaskStatusService fetchTaskStatusService;
    private final AdminFetchTaskService adminFetchTaskService;
    private final AdminFetchJobCounterService adminFetchJobCounterService;
    private final AdminFetchTaskDao adminFetchTaskDao;

    @RabbitListener(queues = TaskProducerRabbitConfig.FETCH_RESULT_QUEUE)
    public void listenFetchTaskStatus(String message,
                                      Channel channel,
                                      @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        boolean success = false;
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
                throw new IllegalArgumentException("task_uuid is missing or blank in fetch task status message");
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
            // 同步更新 admin fetch task 持久化记录
            syncAdminFetchTaskStatus(taskUuid, status, fetchedItemsCount, msg);
            success = true;
        } catch (Exception e) {
            log.error("Failed to handle fetch task status: {}", message, e);
        } finally {
            try {
                if (success) {
                    channel.basicAck(tag, false);
                } else {
                    channel.basicNack(tag, false, false);
                }
            } catch (Exception ackException) {
                log.error("Failed to ack/nack fetch task status message", ackException);
            }
        }
    }

    private void syncAdminFetchTaskStatus(String taskUuid, String status, Integer fetchedItemsCount, String message) {
        try {
            if (taskUuid == null || taskUuid.isBlank()) {
                return;
            }
            int count = fetchedItemsCount == null ? 0 : fetchedItemsCount;
            if (FetchTaskStatusService.STATUS_SUCCESS.equalsIgnoreCase(status)) {
                adminFetchTaskService.markSuccess(taskUuid, count);
            } else if (FetchTaskStatusService.STATUS_FAILURE.equalsIgnoreCase(status)) {
                adminFetchTaskService.markFailure(taskUuid, count, message);
            } else {
                adminFetchTaskService.markRunning(taskUuid);
            }
            // 若该 task 属于某个 job，刷新 job 计数
            AdminFetchTask task = adminFetchTaskDao.getByTaskUuid(taskUuid);
            if (task != null && task.getJobUuid() != null) {
                try {
                    adminFetchJobCounterService.refreshJobCounters(task.getJobUuid());
                } catch (Exception e) {
                    log.error("Failed to refresh job counters for jobUuid={}", task.getJobUuid(), e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to sync admin fetch task status taskUuid={}", taskUuid, e);
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

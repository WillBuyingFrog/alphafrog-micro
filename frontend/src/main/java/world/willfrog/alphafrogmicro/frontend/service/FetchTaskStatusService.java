package world.willfrog.alphafrogmicro.frontend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.frontend.model.FetchTaskStatus;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class FetchTaskStatusService {

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILURE = "failure";

    private final ConcurrentHashMap<String, FetchTaskStatus> statusMap = new ConcurrentHashMap<>();

    public FetchTaskStatus registerTask(String taskUuid, String taskName, Integer taskSubType) {
        long now = System.currentTimeMillis();
        FetchTaskStatus status = new FetchTaskStatus();
        status.setTaskUuid(taskUuid);
        status.setTaskName(taskName);
        status.setTaskSubType(taskSubType);
        status.setStatus(STATUS_RUNNING);
        status.setFetchedItemsCount(0);
        status.setStartedAt(now);
        status.setUpdatedAt(now);
        statusMap.put(taskUuid, status);
        if (log.isDebugEnabled()) {
            log.debug("Registered fetch task status task_uuid={} task_name={} task_sub_type={} status={}",
                    taskUuid, taskName, taskSubType, status.getStatus());
        }
        return status;
    }

    public FetchTaskStatus updateStatus(String taskUuid,
                                        String taskName,
                                        Integer taskSubType,
                                        String status,
                                        Integer fetchedItemsCount,
                                        String message) {
        long now = System.currentTimeMillis();
        FetchTaskStatus previous = statusMap.get(taskUuid);
        if (log.isDebugEnabled()) {
            log.debug("Update fetch task status before task_uuid={} prev_status={} prev_fetched={} prev_updated_at={}",
                    taskUuid,
                    previous == null ? null : previous.getStatus(),
                    previous == null ? null : previous.getFetchedItemsCount(),
                    previous == null ? null : previous.getUpdatedAt());
        }
        FetchTaskStatus updated = statusMap.compute(taskUuid, (key, existing) -> {
            FetchTaskStatus target = existing == null ? new FetchTaskStatus() : existing;
            target.setTaskUuid(taskUuid);
            if (taskName != null && !taskName.isBlank()) {
                target.setTaskName(taskName);
            }
            if (taskSubType != null) {
                target.setTaskSubType(taskSubType);
            }
            target.setStatus(status);
            if (fetchedItemsCount != null) {
                target.setFetchedItemsCount(fetchedItemsCount);
            }
            if (target.getStartedAt() == null) {
                target.setStartedAt(now);
            }
            target.setUpdatedAt(now);
            if (message != null && !message.isBlank()) {
                target.setMessage(message);
            }
            return target;
        });
        if (log.isDebugEnabled()) {
            log.debug("Update fetch task status after task_uuid={} status={} fetched_items={} updated_at={}",
                    taskUuid, updated.getStatus(), updated.getFetchedItemsCount(), updated.getUpdatedAt());
        }
        return updated;
    }

    public FetchTaskStatus markSuccess(String taskUuid,
                                       String taskName,
                                       Integer taskSubType,
                                       int fetchedItemsCount) {
        return updateStatus(taskUuid, taskName, taskSubType, STATUS_SUCCESS, fetchedItemsCount, null);
    }

    public FetchTaskStatus markFailure(String taskUuid,
                                       String taskName,
                                       Integer taskSubType,
                                       int fetchedItemsCount,
                                       String message) {
        return updateStatus(taskUuid, taskName, taskSubType, STATUS_FAILURE, fetchedItemsCount, message);
    }

    public Optional<FetchTaskStatus> getStatus(String taskUuid) {
        FetchTaskStatus status = statusMap.get(taskUuid);
        if (log.isDebugEnabled()) {
            log.debug("Get fetch task status task_uuid={} found={}", taskUuid, status != null);
        }
        return Optional.ofNullable(status);
    }
}

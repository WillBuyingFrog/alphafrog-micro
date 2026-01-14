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
        return status;
    }

    public FetchTaskStatus updateStatus(String taskUuid,
                                        String taskName,
                                        Integer taskSubType,
                                        String status,
                                        Integer fetchedItemsCount,
                                        String message) {
        long now = System.currentTimeMillis();
        return statusMap.compute(taskUuid, (key, existing) -> {
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
        return Optional.ofNullable(statusMap.get(taskUuid));
    }
}

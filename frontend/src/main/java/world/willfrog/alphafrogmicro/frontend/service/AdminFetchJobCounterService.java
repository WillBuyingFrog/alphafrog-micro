package world.willfrog.alphafrogmicro.frontend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.agent.AdminFetchJobDao;
import world.willfrog.alphafrogmicro.common.dao.agent.AdminFetchTaskDao;
import world.willfrog.alphafrogmicro.common.pojo.agent.AdminFetchTask;

import java.time.OffsetDateTime;

/**
 * Admin Fetch Job 计数器刷新服务
 */
@Service
@RequiredArgsConstructor
public class AdminFetchJobCounterService {

    private final AdminFetchJobDao adminFetchJobDao;
    private final AdminFetchTaskDao adminFetchTaskDao;

    public void refreshJobCounters(String jobUuid) {
        int pending = adminFetchTaskDao.countByJobUuidAndStatus(jobUuid, "PENDING");
        int running = adminFetchTaskDao.countByJobUuidAndStatus(jobUuid, "RUNNING");
        int success = adminFetchTaskDao.countByJobUuidAndStatus(jobUuid, "SUCCESS");
        int failure = adminFetchTaskDao.countByJobUuidAndStatus(jobUuid, "FAILURE");

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime finishedAt = null;
        if (pending == 0 && running == 0) {
            finishedAt = now;
        }
        adminFetchJobDao.updateCounters(jobUuid, pending, running, success, failure, now, finishedAt);
    }

    public void refreshJobCountersByTaskUuid(String taskUuid) {
        AdminFetchTask task = adminFetchTaskDao.getByTaskUuid(taskUuid);
        if (task != null && task.getJobUuid() != null) {
            refreshJobCounters(task.getJobUuid());
        }
    }
}

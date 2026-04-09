package world.willfrog.alphafrogmicro.common.pojo.agent;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * admin 抓取任务批次（Job）记录
 */
@Data
public class AdminFetchJob {

    private Long id;

    private String jobUuid;

    private String mode;

    private String label;

    private String status;

    private String requestedSpec;

    private String normalizedSpec;

    private Integer expandedTaskCount;

    private Integer pendingCount;

    private Integer runningCount;

    private Integer successCount;

    private Integer failureCount;

    private String createdBy;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    private OffsetDateTime finishedAt;

    private String executionOptions;
}

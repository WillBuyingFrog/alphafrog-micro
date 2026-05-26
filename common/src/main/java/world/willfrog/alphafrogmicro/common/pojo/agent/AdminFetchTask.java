package world.willfrog.alphafrogmicro.common.pojo.agent;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * admin 抓取任务记录（叶子任务）
 */
@Data
public class AdminFetchTask {

    private Long id;

    private String taskUuid;

    private String templateKey;

    private String taskName;

    private Integer taskSubType;

    private String status;

    private Integer fetchedItemsCount;

    private String message;

    private String paramsSummary;

    private String inputParams;

    private String dispatchPayload;

    private String createdBy;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    private OffsetDateTime finishedAt;

    private String retryOfTaskUuid;

    private String jobUuid;

    private String sourceKind;

    private Integer sourceIndex;

    private String taskSetMode;
}

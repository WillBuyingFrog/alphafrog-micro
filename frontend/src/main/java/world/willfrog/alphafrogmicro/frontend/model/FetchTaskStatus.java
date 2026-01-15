package world.willfrog.alphafrogmicro.frontend.model;

import lombok.Data;

@Data
public class FetchTaskStatus {
    private String taskUuid;
    private String taskName;
    private Integer taskSubType;
    private String status;
    private Integer fetchedItemsCount;
    private Long startedAt;
    private Long updatedAt;
    private String message;
}

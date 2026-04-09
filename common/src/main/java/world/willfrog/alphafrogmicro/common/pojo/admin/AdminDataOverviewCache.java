package world.willfrog.alphafrogmicro.common.pojo.admin;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * admin 数据概览缓存记录
 */
@Data
public class AdminDataOverviewCache {

    private Long id;

    private Long fundCount;

    private Long indexCount;

    private Long stockCount;

    private Long fundNavCount;

    private Long indexDailyCount;

    private Long stockDailyCount;

    private OffsetDateTime cachedAt;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}

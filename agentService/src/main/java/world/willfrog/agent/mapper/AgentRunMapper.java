package world.willfrog.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.model.AgentRunStatus;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface AgentRunMapper {

    int insert(AgentRun run);

    AgentRun findById(@Param("id") String id);

    AgentRun findByIdAndUser(@Param("id") String id, @Param("userId") String userId);

    List<AgentRun> listByUser(@Param("userId") String userId,
                              @Param("status") AgentRunStatus status,
                              @Param("fromTime") OffsetDateTime fromTime,
                              @Param("limit") int limit,
                              @Param("offset") int offset);

    int countByUser(@Param("userId") String userId,
                    @Param("status") AgentRunStatus status,
                    @Param("fromTime") OffsetDateTime fromTime);

    int sumCompletedCreditsByUser(@Param("userId") String userId);

    int updateStatus(@Param("id") String id,
                     @Param("userId") String userId,
                     @Param("status") AgentRunStatus status);

    int updateStatusWithTtl(@Param("id") String id,
                            @Param("userId") String userId,
                            @Param("status") AgentRunStatus status,
                            @Param("ttlExpiresAt") OffsetDateTime ttlExpiresAt);

    int updatePlan(@Param("id") String id,
                   @Param("userId") String userId,
                   @Param("status") AgentRunStatus status,
                   @Param("planJson") String planJson);

    int updateExt(@Param("id") String id,
                  @Param("userId") String userId,
                  @Param("ext") String ext);

    int updateSnapshot(@Param("id") String id,
                       @Param("userId") String userId,
                       @Param("status") AgentRunStatus status,
                       @Param("snapshotJson") String snapshotJson,
                       @Param("completed") boolean completed,
                       @Param("lastError") String lastError);

    int resetForResume(@Param("id") String id,
                       @Param("userId") String userId,
                       @Param("ttlExpiresAt") OffsetDateTime ttlExpiresAt);

    /**
     * 根据 run ID 和用户 ID 删除指定的 Agent Run。
     * <p>
     * 说明：
     * <ul>
     *   <li>此删除为物理删除，直接从数据库移除记录</li>
     *   <li>必须同时匹配 id 和 user_id，防止用户删除他人的 run</li>
     * </ul>
     *
     * @param id     run 的唯一标识 ID
     * @param userId 用户 ID，用于权限校验
     * @return 实际删除的记录数（成功为 1，未找到匹配记录为 0）
     */
    int deleteByIdAndUser(@Param("id") String id, @Param("userId") String userId);
}

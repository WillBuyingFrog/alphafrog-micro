package world.willfrog.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.model.AgentRunStatus;

import java.time.OffsetDateTime;

@Mapper
public interface AgentRunMapper {

    int insert(AgentRun run);

    AgentRun findById(@Param("id") String id);

    AgentRun findByIdAndUser(@Param("id") String id, @Param("userId") String userId);

    int updateStatus(@Param("id") String id,
                     @Param("userId") String userId,
                     @Param("status") AgentRunStatus status);

    int updatePlan(@Param("id") String id,
                   @Param("userId") String userId,
                   @Param("status") AgentRunStatus status,
                   @Param("planJson") String planJson);

    int updateSnapshot(@Param("id") String id,
                       @Param("userId") String userId,
                       @Param("status") AgentRunStatus status,
                       @Param("snapshotJson") String snapshotJson,
                       @Param("completed") boolean completed,
                       @Param("lastError") String lastError);

    int resetForResume(@Param("id") String id,
                       @Param("userId") String userId,
                       @Param("ttlExpiresAt") OffsetDateTime ttlExpiresAt);
}

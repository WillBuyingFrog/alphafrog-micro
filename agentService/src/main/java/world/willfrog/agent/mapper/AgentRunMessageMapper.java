package world.willfrog.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.entity.AgentRunMessage;

import java.util.List;

/**
 * Agent Run 消息 Mapper。
 * <p>
 * 职责：
 * 1. 消息 CRUD 操作
 * 2. 按 run_id 查询消息历史（支持分页）
 * 3. 获取下一个消息序号（用于 seq 分配）
 * 4. 批量插入消息
 *
 * @author kimi
 */
@Mapper
public interface AgentRunMessageMapper {

    /**
     * 插入单条消息。
     *
     * @param message 消息实体
     * @return 影响行数
     */
    int insert(AgentRunMessage message);

    /**
     * 根据 ID 查询消息。
     *
     * @param id 消息 ID
     * @return 消息实体
     */
    AgentRunMessage findById(Long id);

    /**
     * 根据 run_id 和 seq 查询消息。
     *
     * @param runId Run ID
     * @param seq   消息序号
     * @return 消息实体
     */
    AgentRunMessage findByRunIdAndSeq(@Param("runId") String runId, @Param("seq") Integer seq);

    /**
     * 查询指定 run 的所有消息（按 seq 升序）。
     *
     * @param runId Run ID
     * @return 消息列表
     */
    List<AgentRunMessage> listByRunId(@Param("runId") String runId);

    /**
     * 分页查询指定 run 的消息（按 seq 升序）。
     *
     * @param runId  Run ID
     * @param limit  每页数量
     * @param offset 偏移量
     * @return 消息列表
     */
    List<AgentRunMessage> listByRunIdWithPagination(@Param("runId") String runId,
                                                     @Param("limit") Integer limit,
                                                     @Param("offset") Integer offset);

    /**
     * 查询指定 run 的消息总数。
     *
     * @param runId Run ID
     * @return 消息数量
     */
    int countByRunId(@Param("runId") String runId);

    /**
     * 获取指定 run 的下一个消息序号。
     * <p>
     * 实现说明：使用 COALESCE(MAX(seq), 0) + 1 计算下一个序号
     *
     * @param runId Run ID
     * @return 下一个序号（从 1 开始）
     */
    Integer getNextSeq(@Param("runId") String runId);

    /**
     * 查询指定 run 的最新消息（按 seq 降序取第一条）。
     *
     * @param runId Run ID
     * @return 最新消息
     */
    AgentRunMessage findLatestByRunId(@Param("runId") String runId);

    /**
     * 查询指定 run 的最新用户消息（按 seq 降序取第一条）。
     *
     * @param runId Run ID
     * @return 最新用户消息
     */
    AgentRunMessage findLatestUserByRunId(@Param("runId") String runId);

    /**
     * 删除指定 run 的所有消息（级联删除已在数据库层通过 ON DELETE CASCADE 实现）。
     * <p>
     * 注意：通常不需要调用此方法，因为删除 run 时会自动级联删除消息。
     * 此方法仅用于特殊场景下的手动清理。
     *
     * @param runId Run ID
     * @return 影响行数
     */
    int deleteByRunId(@Param("runId") String runId);

    /**
     * 分页查询指定 run 的消息（排除 initial 消息，按 seq 升序）。
     *
     * @param runId  Run ID
     * @param limit  每页数量
     * @param offset 偏移量
     * @return 消息列表
     */
    List<AgentRunMessage> listByRunIdWithPaginationExcludeInitial(@Param("runId") String runId,
                                                                  @Param("limit") Integer limit,
                                                                  @Param("offset") Integer offset);

    /**
     * 查询指定 run 的消息总数（排除 initial 消息）。
     *
     * @param runId Run ID
     * @return 消息数量
     */
    int countByRunIdExcludeInitial(@Param("runId") String runId);
}

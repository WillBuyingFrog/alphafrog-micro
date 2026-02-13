package world.willfrog.alphafrogmicro.common.dao.agent;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.agent.AdminIdempotency;

@Mapper
public interface AdminIdempotencyDao {

    @Insert("INSERT INTO alphafrog_admin_idempotency (" +
            "operator_id, action, target_id, idempotency_key, request_hash, status, response_json" +
            ") VALUES (" +
            "#{operatorId}, #{action}, #{targetId}, #{idempotencyKey}, #{requestHash}, #{status}, #{responseJson}" +
            ") ON CONFLICT (operator_id, action, target_id, idempotency_key) DO NOTHING")
    int insertProcessing(@Param("operatorId") String operatorId,
                         @Param("action") String action,
                         @Param("targetId") String targetId,
                         @Param("idempotencyKey") String idempotencyKey,
                         @Param("requestHash") String requestHash,
                         @Param("status") String status,
                         @Param("responseJson") String responseJson);

    @Select("SELECT * FROM alphafrog_admin_idempotency " +
            "WHERE operator_id = #{operatorId} AND action = #{action} " +
            "AND target_id = #{targetId} AND idempotency_key = #{idempotencyKey} LIMIT 1")
    @Results(id = "adminIdempotencyResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "operatorId", column = "operator_id"),
            @Result(property = "action", column = "action"),
            @Result(property = "targetId", column = "target_id"),
            @Result(property = "idempotencyKey", column = "idempotency_key"),
            @Result(property = "requestHash", column = "request_hash"),
            @Result(property = "status", column = "status"),
            @Result(property = "responseJson", column = "response_json"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at")
    })
    AdminIdempotency find(@Param("operatorId") String operatorId,
                          @Param("action") String action,
                          @Param("targetId") String targetId,
                          @Param("idempotencyKey") String idempotencyKey);

    @Update("UPDATE alphafrog_admin_idempotency " +
            "SET status = #{status}, response_json = #{responseJson}, updated_at = CURRENT_TIMESTAMP " +
            "WHERE id = #{id}")
    int markCompleted(@Param("id") Long id,
                      @Param("status") String status,
                      @Param("responseJson") String responseJson);
}

package world.willfrog.alphafrogmicro.common.dao.agent;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.agent.AdminAuditLog;

import java.util.List;

@Mapper
public interface AdminAuditLogDao {

    @Insert("INSERT INTO alphafrog_admin_audit_log (" +
            "audit_id, operator_id, action, target_type, target_id, before_json, after_json, reason, idempotency_key" +
            ") VALUES (" +
            "#{auditId}, #{operatorId}, #{action}, #{targetType}, #{targetId}, CAST(#{beforeJson} AS jsonb), CAST(#{afterJson} AS jsonb), #{reason}, #{idempotencyKey}" +
            ")")
    int insert(AdminAuditLog auditLog);

    @Select("SELECT * FROM alphafrog_admin_audit_log " +
            "WHERE target_type = #{targetType} AND target_id = #{targetId} " +
            "ORDER BY created_at DESC LIMIT #{limit}")
    @Results(id = "adminAuditLogResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "auditId", column = "audit_id"),
            @Result(property = "operatorId", column = "operator_id"),
            @Result(property = "action", column = "action"),
            @Result(property = "targetType", column = "target_type"),
            @Result(property = "targetId", column = "target_id"),
            @Result(property = "beforeJson", column = "before_json"),
            @Result(property = "afterJson", column = "after_json"),
            @Result(property = "reason", column = "reason"),
            @Result(property = "idempotencyKey", column = "idempotency_key"),
            @Result(property = "createdAt", column = "created_at")
    })
    List<AdminAuditLog> listByTarget(@Param("targetType") String targetType,
                                     @Param("targetId") String targetId,
                                     @Param("limit") int limit);
}

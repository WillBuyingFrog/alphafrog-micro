package world.willfrog.alphafrogmicro.common.dao.config;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigAuditLog;

import java.util.List;

@Mapper
public interface ConfigAuditLogDao {

    @Insert("INSERT INTO alphafrog_config_audit_log (type_id, action, snapshot_id, base_version, operator_id, reason) " +
            "VALUES (#{typeId}, #{action}, #{snapshotId}, #{baseVersion}, #{operatorId}, #{reason})")
    int insert(ConfigAuditLog auditLog);

    @Select("SELECT * FROM alphafrog_config_audit_log WHERE type_id = #{typeId} ORDER BY created_at DESC LIMIT #{limit}")
    @Results(id = "configAuditLogResult", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "typeId", column = "type_id"),
            @Result(property = "action", column = "action"),
            @Result(property = "snapshotId", column = "snapshot_id"),
            @Result(property = "baseVersion", column = "base_version"),
            @Result(property = "operatorId", column = "operator_id"),
            @Result(property = "reason", column = "reason"),
            @Result(property = "createdAt", column = "created_at")
    })
    List<ConfigAuditLog> listByType(@Param("typeId") Integer typeId, @Param("limit") int limit);
}

package world.willfrog.alphafrogmicro.common.dao.config;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigAuditLog;

@Mapper
public interface ConfigAuditLogDao {

    @Insert("INSERT INTO alphafrog_config_audit_log (type_id, action, snapshot_id, base_version, operator_id, reason) " +
            "VALUES (#{typeId}, #{action}, #{snapshotId}, #{baseVersion}, #{operatorId}, #{reason})")
    int insert(ConfigAuditLog auditLog);
}

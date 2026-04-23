package world.willfrog.alphafrogmicro.common.dao.config;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigActive;

@Mapper
public interface ConfigActiveDao {

    @Insert("INSERT INTO alphafrog_config_active (type_id, snapshot_id, activated_at, activated_by) " +
            "VALUES (#{typeId}, #{snapshotId}, #{activatedAt}, #{activatedBy}) " +
            "ON CONFLICT (type_id) DO UPDATE SET snapshot_id = EXCLUDED.snapshot_id, " +
            "activated_at = EXCLUDED.activated_at, activated_by = EXCLUDED.activated_by")
    int upsert(ConfigActive configActive);

    @Select("SELECT * FROM alphafrog_config_active WHERE type_id = #{typeId}")
    @Results(id = "configActiveResult", value = {
            @Result(property = "typeId", column = "type_id"),
            @Result(property = "snapshotId", column = "snapshot_id"),
            @Result(property = "activatedAt", column = "activated_at"),
            @Result(property = "activatedBy", column = "activated_by")
    })
    ConfigActive getByType(@Param("typeId") Integer typeId);
}

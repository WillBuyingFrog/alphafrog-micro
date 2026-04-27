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

    @Insert("INSERT INTO alphafrog_config_active (type_id, snapshot_id, activated_at, activated_by) " +
            "VALUES (#{typeId}, #{snapshotId}, #{activatedAt}, #{activatedBy}) " +
            "ON CONFLICT (type_id) DO NOTHING")
    int insertIfAbsent(ConfigActive configActive);

    @Update("UPDATE alphafrog_config_active " +
            "SET snapshot_id = #{snapshotId}, activated_at = #{activatedAt}, activated_by = #{activatedBy} " +
            "WHERE type_id = #{typeId} AND snapshot_id = #{expectedSnapshotId}")
    int updateIfSnapshotMatches(@Param("typeId") Integer typeId,
                                @Param("snapshotId") Integer snapshotId,
                                @Param("expectedSnapshotId") Integer expectedSnapshotId,
                                @Param("activatedAt") java.time.OffsetDateTime activatedAt,
                                @Param("activatedBy") String activatedBy);

    @Select("SELECT * FROM alphafrog_config_active WHERE type_id = #{typeId}")
    @Results(id = "configActiveResult", value = {
            @Result(property = "typeId", column = "type_id"),
            @Result(property = "snapshotId", column = "snapshot_id"),
            @Result(property = "activatedAt", column = "activated_at"),
            @Result(property = "activatedBy", column = "activated_by")
    })
    ConfigActive getByType(@Param("typeId") Integer typeId);
}

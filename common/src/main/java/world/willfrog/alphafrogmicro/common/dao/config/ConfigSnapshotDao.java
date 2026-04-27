package world.willfrog.alphafrogmicro.common.dao.config;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigSnapshot;

import java.util.List;

@Mapper
public interface ConfigSnapshotDao {

    @Insert("INSERT INTO alphafrog_config_snapshot (type_id, version, content_json, content_md5, comment, created_by) " +
            "VALUES (#{typeId}, #{version}, CAST(#{contentJson} AS jsonb), #{contentMd5}, #{comment}, #{createdBy})")
    int insert(ConfigSnapshot snapshot);

    @Select("SELECT * FROM alphafrog_config_snapshot WHERE type_id = #{typeId} AND version = #{version}")
    @Results(id = "configSnapshotResult", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "typeId", column = "type_id"),
            @Result(property = "version", column = "version"),
            @Result(property = "contentJson", column = "content_json"),
            @Result(property = "contentMd5", column = "content_md5"),
            @Result(property = "comment", column = "comment"),
            @Result(property = "createdBy", column = "created_by"),
            @Result(property = "createdAt", column = "created_at")
    })
    ConfigSnapshot getByTypeAndVersion(@Param("typeId") Integer typeId, @Param("version") String version);

    @Select("SELECT * FROM alphafrog_config_snapshot WHERE type_id = #{typeId} ORDER BY created_at DESC")
    @ResultMap("configSnapshotResult")
    List<ConfigSnapshot> listByType(@Param("typeId") Integer typeId);

    @Select("SELECT * FROM alphafrog_config_snapshot WHERE id = #{id}")
    @ResultMap("configSnapshotResult")
    ConfigSnapshot getById(@Param("id") Integer id);

    @Select("SELECT COUNT(*) FROM alphafrog_config_snapshot WHERE type_id = #{typeId}")
    int countByType(@Param("typeId") Integer typeId);

    @Select("SELECT COALESCE(MAX(CAST(SUBSTRING(version FROM 2) AS INTEGER)), 0) FROM alphafrog_config_snapshot WHERE type_id = #{typeId}")
    int maxVersionNumberByType(@Param("typeId") Integer typeId);

    @Delete("DELETE FROM alphafrog_config_snapshot WHERE type_id = #{typeId} AND version = #{version}")
    int deleteByTypeAndVersion(@Param("typeId") Integer typeId, @Param("version") String version);
}

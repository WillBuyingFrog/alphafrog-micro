package world.willfrog.alphafrogmicro.common.dao.config;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigType;

import java.util.List;

@Mapper
public interface ConfigTypeDao {

    @Insert("INSERT INTO alphafrog_config_type (name, data_id, config_group, service_name, schema_json, description) " +
            "VALUES (#{name}, #{dataId}, #{configGroup}, #{serviceName}, CAST(#{schemaJson} AS jsonb), #{description}) " +
            "ON CONFLICT (name) DO NOTHING")
    int insert(ConfigType configType);

    @Select("SELECT * FROM alphafrog_config_type WHERE name = #{name}")
    @Results(id = "configTypeResult", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "name", column = "name"),
            @Result(property = "dataId", column = "data_id"),
            @Result(property = "configGroup", column = "config_group"),
            @Result(property = "serviceName", column = "service_name"),
            @Result(property = "schemaJson", column = "schema_json"),
            @Result(property = "description", column = "description"),
            @Result(property = "createdAt", column = "created_at")
    })
    ConfigType getByName(@Param("name") String name);

    @Select("SELECT * FROM alphafrog_config_type WHERE id = #{id} FOR UPDATE")
    @ResultMap("configTypeResult")
    ConfigType lockById(@Param("id") Integer id);

    @Select("SELECT * FROM alphafrog_config_type ORDER BY name")
    @ResultMap("configTypeResult")
    List<ConfigType> listAll();
}

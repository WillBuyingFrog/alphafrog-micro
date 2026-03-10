package world.willfrog.alphafrogmicro.common.dao.domestic.index;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.SwIndustryClassify;

import java.util.List;

@Mapper
public interface SwIndustryClassifyDao {

    @Insert("INSERT INTO alphafrog_index_sw_classify (index_code, industry_name, parent_code, level, " +
            "industry_code, is_pub, src) " +
            "VALUES (#{indexCode}, #{industryName}, #{parentCode}, #{level}, " +
            "#{industryCode}, #{isPub}, #{src}) " +
            "ON CONFLICT (index_code, src) DO NOTHING")
    int insertSwIndustryClassify(SwIndustryClassify classify);

    @Select("SELECT * FROM alphafrog_index_sw_classify WHERE src = #{src}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "indexCode", column = "index_code"),
            @Result(property = "industryName", column = "industry_name"),
            @Result(property = "parentCode", column = "parent_code"),
            @Result(property = "level", column = "level"),
            @Result(property = "industryCode", column = "industry_code"),
            @Result(property = "isPub", column = "is_pub"),
            @Result(property = "src", column = "src")
    })
    List<SwIndustryClassify> getBySrc(@Param("src") String src);

    @Select("SELECT * FROM alphafrog_index_sw_classify WHERE level = #{level} AND src = #{src}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "indexCode", column = "index_code"),
            @Result(property = "industryName", column = "industry_name"),
            @Result(property = "parentCode", column = "parent_code"),
            @Result(property = "level", column = "level"),
            @Result(property = "industryCode", column = "industry_code"),
            @Result(property = "isPub", column = "is_pub"),
            @Result(property = "src", column = "src")
    })
    List<SwIndustryClassify> getByLevelAndSrc(@Param("level") String level, @Param("src") String src);

    @Delete("DELETE FROM alphafrog_index_sw_classify")
    int deleteAll();

    @Delete("DELETE FROM alphafrog_index_sw_classify WHERE src = #{src}")
    int deleteBySrc(@Param("src") String src);
}

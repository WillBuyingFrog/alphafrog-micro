package world.willfrog.alphafrogmicro.common.dao.domestic.index;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.SwIndustryMember;

import java.util.List;

@Mapper
public interface SwIndustryMemberDao {

    @Insert("INSERT INTO alphafrog_index_sw_member (l1_code, l1_name, l2_code, l2_name, l3_code, l3_name, " +
            "ts_code, name, in_date, out_date, is_new) " +
            "VALUES (#{l1Code}, #{l1Name}, #{l2Code}, #{l2Name}, #{l3Code}, #{l3Name}, " +
            "#{tsCode}, #{name}, #{inDate}, #{outDate}, #{isNew}) " +
            "ON CONFLICT (ts_code, l3_code, in_date) DO NOTHING")
    int insertSwIndustryMember(SwIndustryMember member);

    @Select("SELECT * FROM alphafrog_index_sw_member WHERE ts_code = #{tsCode}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "l1Code", column = "l1_code"),
            @Result(property = "l1Name", column = "l1_name"),
            @Result(property = "l2Code", column = "l2_code"),
            @Result(property = "l2Name", column = "l2_name"),
            @Result(property = "l3Code", column = "l3_code"),
            @Result(property = "l3Name", column = "l3_name"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "name", column = "name"),
            @Result(property = "inDate", column = "in_date"),
            @Result(property = "outDate", column = "out_date"),
            @Result(property = "isNew", column = "is_new")
    })
    List<SwIndustryMember> getByTsCode(@Param("tsCode") String tsCode);

    @Select("SELECT * FROM alphafrog_index_sw_member WHERE l1_code = #{l1Code} AND is_new = 'Y'")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "l1Code", column = "l1_code"),
            @Result(property = "l1Name", column = "l1_name"),
            @Result(property = "l2Code", column = "l2_code"),
            @Result(property = "l2Name", column = "l2_name"),
            @Result(property = "l3Code", column = "l3_code"),
            @Result(property = "l3Name", column = "l3_name"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "name", column = "name"),
            @Result(property = "inDate", column = "in_date"),
            @Result(property = "outDate", column = "out_date"),
            @Result(property = "isNew", column = "is_new")
    })
    List<SwIndustryMember> getByL1Code(@Param("l1Code") String l1Code);

    @Delete("DELETE FROM alphafrog_index_sw_member")
    int deleteAll();

    @Delete("DELETE FROM alphafrog_index_sw_member WHERE ts_code = #{tsCode}")
    int deleteByTsCode(@Param("tsCode") String tsCode);
}

package world.willfrog.alphafrogmicro.common.dao.domestic.fund;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundManager;

import java.util.List;

@Mapper
public interface FundManagerDao {

    @Insert("INSERT INTO alphafrog_fund_manager (ts_code, ann_date, name, gender, birth_year, edu, " +
            "nationality, begin_date, end_date, resume, extended) " +
            "VALUES (#{tsCode}, #{annDate}, #{name}, #{gender}, #{birthYear}, #{edu}, " +
            "#{nationality}, #{beginDate}, #{endDate}, #{resume}, #{extended}) " +
            "ON CONFLICT (ts_code, name, begin_date) DO NOTHING")
    int insertFundManager(FundManager fundManager);

    @Select("SELECT * FROM alphafrog_fund_manager WHERE ts_code = #{tsCode}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "annDate", column = "ann_date"),
            @Result(property = "name", column = "name"),
            @Result(property = "gender", column = "gender"),
            @Result(property = "birthYear", column = "birth_year"),
            @Result(property = "edu", column = "edu"),
            @Result(property = "nationality", column = "nationality"),
            @Result(property = "beginDate", column = "begin_date"),
            @Result(property = "endDate", column = "end_date"),
            @Result(property = "resume", column = "resume"),
            @Result(property = "extended", column = "extended")
    })
    List<FundManager> getByTsCode(@Param("tsCode") String tsCode);

    @Delete("DELETE FROM alphafrog_fund_manager WHERE ts_code = #{tsCode}")
    int deleteByTsCode(@Param("tsCode") String tsCode);
}

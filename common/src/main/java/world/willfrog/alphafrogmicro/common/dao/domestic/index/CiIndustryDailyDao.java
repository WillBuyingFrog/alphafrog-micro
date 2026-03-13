package world.willfrog.alphafrogmicro.common.dao.domestic.index;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.CiIndustryDaily;

import java.util.List;

@Mapper
public interface CiIndustryDailyDao {

    @Insert("INSERT INTO alphafrog_index_ci_daily (ts_code, trade_date, name, open, low, high, close, " +
            "pre_close, change_val, pct_change, vol, amount, extended) " +
            "VALUES (#{tsCode}, #{tradeDate}, #{name}, #{open}, #{low}, #{high}, #{close}, " +
            "#{preClose}, #{changeVal}, #{pctChange}, #{vol}, #{amount}, #{extended}) " +
            "ON CONFLICT (ts_code, trade_date) DO NOTHING")
    int insertCiIndustryDaily(CiIndustryDaily daily);

    @Select("SELECT * FROM alphafrog_index_ci_daily WHERE ts_code = #{tsCode} " +
            "AND trade_date BETWEEN #{startDate} AND #{endDate}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "tradeDate", column = "trade_date"),
            @Result(property = "name", column = "name"),
            @Result(property = "open", column = "open"),
            @Result(property = "low", column = "low"),
            @Result(property = "high", column = "high"),
            @Result(property = "close", column = "close"),
            @Result(property = "preClose", column = "pre_close"),
            @Result(property = "changeVal", column = "change_val"),
            @Result(property = "pctChange", column = "pct_change"),
            @Result(property = "vol", column = "vol"),
            @Result(property = "amount", column = "amount"),
            @Result(property = "extended", column = "extended")
    })
    List<CiIndustryDaily> getByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                                   @Param("startDate") long startDate,
                                                   @Param("endDate") long endDate);

    @Delete("DELETE FROM alphafrog_index_ci_daily WHERE ts_code = #{tsCode}")
    int deleteByTsCode(@Param("tsCode") String tsCode);

    @Delete("DELETE FROM alphafrog_index_ci_daily WHERE trade_date BETWEEN #{startDate} AND #{endDate}")
    int deleteByDateRange(@Param("startDate") long startDate, @Param("endDate") long endDate);

    @Delete("DELETE FROM alphafrog_index_ci_daily WHERE ts_code = #{tsCode} " +
            "AND trade_date BETWEEN #{startDate} AND #{endDate}")
    int deleteByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                   @Param("startDate") long startDate,
                                   @Param("endDate") long endDate);
}

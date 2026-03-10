package world.willfrog.alphafrogmicro.common.dao.domestic.index;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.SwIndustryDaily;

import java.util.List;

@Mapper
public interface SwIndustryDailyDao {

    @Insert("INSERT INTO alphafrog_index_sw_daily (ts_code, trade_date, name, open, low, high, close, " +
            "change_val, pct_change, vol, amount, pe, pb, float_mv, total_mv) " +
            "VALUES (#{tsCode}, #{tradeDate}, #{name}, #{open}, #{low}, #{high}, #{close}, " +
            "#{changeVal}, #{pctChange}, #{vol}, #{amount}, #{pe}, #{pb}, #{floatMv}, #{totalMv}) " +
            "ON CONFLICT (ts_code, trade_date) DO NOTHING")
    int insertSwIndustryDaily(SwIndustryDaily daily);

    @Select("SELECT * FROM alphafrog_index_sw_daily WHERE ts_code = #{tsCode} " +
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
            @Result(property = "changeVal", column = "change_val"),
            @Result(property = "pctChange", column = "pct_change"),
            @Result(property = "vol", column = "vol"),
            @Result(property = "amount", column = "amount"),
            @Result(property = "pe", column = "pe"),
            @Result(property = "pb", column = "pb"),
            @Result(property = "floatMv", column = "float_mv"),
            @Result(property = "totalMv", column = "total_mv")
    })
    List<SwIndustryDaily> getByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                                   @Param("startDate") long startDate,
                                                   @Param("endDate") long endDate);

    @Delete("DELETE FROM alphafrog_index_sw_daily WHERE ts_code = #{tsCode}")
    int deleteByTsCode(@Param("tsCode") String tsCode);

    @Delete("DELETE FROM alphafrog_index_sw_daily WHERE trade_date BETWEEN #{startDate} AND #{endDate}")
    int deleteByDateRange(@Param("startDate") long startDate, @Param("endDate") long endDate);

    @Delete("DELETE FROM alphafrog_index_sw_daily WHERE ts_code = #{tsCode} " +
            "AND trade_date BETWEEN #{startDate} AND #{endDate}")
    int deleteByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                   @Param("startDate") long startDate,
                                   @Param("endDate") long endDate);
}

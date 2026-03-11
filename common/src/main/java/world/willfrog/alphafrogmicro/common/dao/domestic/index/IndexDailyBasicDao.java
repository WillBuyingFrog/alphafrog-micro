package world.willfrog.alphafrogmicro.common.dao.domestic.index;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexDailyBasic;

import java.util.List;

@Mapper
public interface IndexDailyBasicDao {

    @Insert("INSERT INTO alphafrog_index_daily_basic (ts_code, trade_date, total_mv, float_mv, total_share, " +
            "float_share, free_share, turnover_rate, turnover_rate_f, pe, pe_ttm, pb, extended) " +
            "VALUES (#{tsCode}, #{tradeDate}, #{totalMv}, #{floatMv}, #{totalShare}, " +
            "#{floatShare}, #{freeShare}, #{turnoverRate}, #{turnoverRateF}, #{pe}, #{peTtm}, #{pb}, #{extended}) " +
            "ON CONFLICT (ts_code, trade_date) DO NOTHING")
    int insertIndexDailyBasic(IndexDailyBasic indexDailyBasic);

    @Select("SELECT * FROM alphafrog_index_daily_basic WHERE ts_code = #{tsCode} " +
            "AND trade_date BETWEEN #{startDate} AND #{endDate}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "tradeDate", column = "trade_date"),
            @Result(property = "totalMv", column = "total_mv"),
            @Result(property = "floatMv", column = "float_mv"),
            @Result(property = "totalShare", column = "total_share"),
            @Result(property = "floatShare", column = "float_share"),
            @Result(property = "freeShare", column = "free_share"),
            @Result(property = "turnoverRate", column = "turnover_rate"),
            @Result(property = "turnoverRateF", column = "turnover_rate_f"),
            @Result(property = "pe", column = "pe"),
            @Result(property = "peTtm", column = "pe_ttm"),
            @Result(property = "pb", column = "pb"),
            @Result(property = "extended", column = "extended")
    })
    List<IndexDailyBasic> getByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                                   @Param("startDate") long startDate,
                                                   @Param("endDate") long endDate);

    @Delete("DELETE FROM alphafrog_index_daily_basic WHERE ts_code = #{tsCode}")
    int deleteByTsCode(@Param("tsCode") String tsCode);

    @Delete("DELETE FROM alphafrog_index_daily_basic WHERE trade_date BETWEEN #{startDate} AND #{endDate}")
    int deleteByDateRange(@Param("startDate") long startDate, @Param("endDate") long endDate);

    @Delete("DELETE FROM alphafrog_index_daily_basic WHERE ts_code = #{tsCode} " +
            "AND trade_date BETWEEN #{startDate} AND #{endDate}")
    int deleteByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                   @Param("startDate") long startDate,
                                   @Param("endDate") long endDate);
}

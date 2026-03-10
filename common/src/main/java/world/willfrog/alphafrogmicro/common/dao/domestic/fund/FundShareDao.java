package world.willfrog.alphafrogmicro.common.dao.domestic.fund;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundShare;

import java.util.List;

@Mapper
public interface FundShareDao {

    @Insert("INSERT INTO alphafrog_fund_share (ts_code, trade_date, fd_share) " +
            "VALUES (#{tsCode}, #{tradeDate}, #{fdShare}) " +
            "ON CONFLICT (ts_code, trade_date) DO NOTHING")
    int insertFundShare(FundShare fundShare);

    @Select("SELECT * FROM alphafrog_fund_share WHERE ts_code = #{tsCode} " +
            "AND trade_date BETWEEN #{startDate} AND #{endDate}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "tradeDate", column = "trade_date"),
            @Result(property = "fdShare", column = "fd_share")
    })
    List<FundShare> getByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                            @Param("startDate") long startDate,
                                            @Param("endDate") long endDate);

    @Delete("DELETE FROM alphafrog_fund_share WHERE ts_code = #{tsCode}")
    int deleteByTsCode(@Param("tsCode") String tsCode);

    @Delete("DELETE FROM alphafrog_fund_share WHERE trade_date BETWEEN #{startDate} AND #{endDate}")
    int deleteByDateRange(@Param("startDate") long startDate, @Param("endDate") long endDate);

    @Delete("DELETE FROM alphafrog_fund_share WHERE ts_code = #{tsCode} " +
            "AND trade_date BETWEEN #{startDate} AND #{endDate}")
    int deleteByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                   @Param("startDate") long startDate,
                                   @Param("endDate") long endDate);
}

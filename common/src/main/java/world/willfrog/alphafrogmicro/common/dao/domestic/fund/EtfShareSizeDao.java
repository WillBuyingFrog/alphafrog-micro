package world.willfrog.alphafrogmicro.common.dao.domestic.fund;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.EtfShareSize;

import java.util.List;

@Mapper
public interface EtfShareSizeDao {

    @Insert("INSERT INTO alphafrog_etf_share_size (trade_date, ts_code, etf_name, total_share, total_size, " +
            "nav, close, exchange) " +
            "VALUES (#{tradeDate}, #{tsCode}, #{etfName}, #{totalShare}, #{totalSize}, " +
            "#{nav}, #{close}, #{exchange}) " +
            "ON CONFLICT (ts_code, trade_date) DO NOTHING")
    int insertEtfShareSize(EtfShareSize etfShareSize);

    @Select("SELECT * FROM alphafrog_etf_share_size WHERE ts_code = #{tsCode} " +
            "AND trade_date BETWEEN #{startDate} AND #{endDate}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tradeDate", column = "trade_date"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "etfName", column = "etf_name"),
            @Result(property = "totalShare", column = "total_share"),
            @Result(property = "totalSize", column = "total_size"),
            @Result(property = "nav", column = "nav"),
            @Result(property = "close", column = "close"),
            @Result(property = "exchange", column = "exchange")
    })
    List<EtfShareSize> getByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                                @Param("startDate") long startDate,
                                                @Param("endDate") long endDate);

    @Delete("DELETE FROM alphafrog_etf_share_size WHERE ts_code = #{tsCode}")
    int deleteByTsCode(@Param("tsCode") String tsCode);

    @Delete("DELETE FROM alphafrog_etf_share_size WHERE trade_date BETWEEN #{startDate} AND #{endDate}")
    int deleteByDateRange(@Param("startDate") long startDate, @Param("endDate") long endDate);

    @Delete("DELETE FROM alphafrog_etf_share_size WHERE ts_code = #{tsCode} " +
            "AND trade_date BETWEEN #{startDate} AND #{endDate}")
    int deleteByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                   @Param("startDate") long startDate,
                                   @Param("endDate") long endDate);
}

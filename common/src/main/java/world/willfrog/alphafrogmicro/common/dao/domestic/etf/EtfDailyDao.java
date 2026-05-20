package world.willfrog.alphafrogmicro.common.dao.domestic.etf;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.etf.EtfDaily;

import java.util.List;

@Mapper
public interface EtfDailyDao {

    @Insert("INSERT INTO alphafrog_domestic_etf_daily (ts_code, trade_date, open, high, low, close, pre_close, " +
            "change, pct_chg, vol, amount) VALUES (#{tsCode}, #{tradeDate}, #{open}, #{high}, #{low}, #{close}, " +
            "#{preClose}, #{change}, #{pctChg}, #{vol}, #{amount}) " +
            "ON CONFLICT (ts_code, trade_date) DO NOTHING")
    int insertEtfDaily(EtfDaily etfDaily);

    @Select("SELECT * FROM alphafrog_domestic_etf_daily WHERE ts_code = #{tsCode} " +
            "AND trade_date BETWEEN #{startDate} AND #{endDate} ORDER BY trade_date")
    @Results({
            @Result(property = "etfDailyId", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "tradeDate", column = "trade_date"),
            @Result(property = "open", column = "open"),
            @Result(property = "high", column = "high"),
            @Result(property = "low", column = "low"),
            @Result(property = "close", column = "close"),
            @Result(property = "preClose", column = "pre_close"),
            @Result(property = "change", column = "change"),
            @Result(property = "pctChg", column = "pct_chg"),
            @Result(property = "vol", column = "vol"),
            @Result(property = "amount", column = "amount")
    })
    List<EtfDaily> getByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                          @Param("startDate") long startDate,
                                          @Param("endDate") long endDate);
}

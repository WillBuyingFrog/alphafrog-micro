package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockDaily;

import java.util.List;

@Mapper
public interface StockQuoteDao {

    @Insert({
            "INSERT INTO alphafrog_stock_daily (ts_code, trade_date, close, open, high, low, pre_close, change, pct_chg, vol, amount) " +
                    "VALUES (#{tsCode}, #{tradeDate}, #{close}, #{open}, #{high}, #{low}, #{preClose}, #{change}," +
                    " #{pctChg}, #{vol}, #{amount})" +
                    "ON CONFLICT(ts_code, trade_date) DO NOTHING"
    })
    int insertStockDaily(StockDaily stockDaily);

    @Select("SELECT * FROM alphafrog_stock_daily WHERE ts_code = #{tsCode} AND trade_date between #{startDate} and #{endDate}")
    @Results({
            @Result(property = "stockDailyId", column = "id", id = true),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "tradeDate", column = "trade_date"),
            @Result(property = "close", column = "close"),
            @Result(property = "open", column = "open"),
            @Result(property = "high", column = "high"),
            @Result(property = "low", column = "low"),
            @Result(property = "preClose", column = "pre_close"),
            @Result(property = "change", column = "change"),
            @Result(property = "pctChg", column = "pct_chg"),
            @Result(property = "vol", column = "vol"),
            @Result(property = "amount", column = "amount")
    })
    List<StockDaily> getStockDailyByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                                       @Param("startDate") long startDate, @Param("endDate") long endDate);

    @Select("SELECT * FROM alphafrog_stock_daily WHERE trade_date = #{tradeDateTimestamp}")
    List<StockDaily> getStockDailyByTradeDate(@Param("tradeDateTimestamp") long tradeDateTimestamp);
}

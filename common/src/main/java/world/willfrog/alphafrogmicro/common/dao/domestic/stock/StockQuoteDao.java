package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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
    List<StockDaily> getStockDailyByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                                       @Param("startDate") long startDate, @Param("endDate") long endDate);

    @Select("SELECT * FROM alphafrog_stock_daily WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}")
    List<StockDaily> getStockDailyByTradeDate(long tradeDateTimestamp);
}

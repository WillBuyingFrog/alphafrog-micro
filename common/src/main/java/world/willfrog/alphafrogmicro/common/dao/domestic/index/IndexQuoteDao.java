package world.willfrog.alphafrogmicro.common.dao.domestic.index;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexDaily;

import java.util.List;

public interface IndexQuoteDao {
    @Insert("INSERT INTO alphafrog_index_daily (ts_code, trade_date, close, open, high, low, pre_close, change, pct_chg, vol, amount) " +
            "VALUES (#{tsCode}, #{tradeDate}, #{close}, #{open}, #{high}, #{low}, #{preClose}, #{change}, #{pctChg}, #{vol}, #{amount})" +
            "ON CONFLICT (ts_code, trade_date) DO NOTHING")
    int insertIndexDaily(IndexDaily indexDaily);

    @Select("SELECT * FROM alphafrog_index_daily WHERE ts_code = #{tsCode} AND trade_date BETWEEN #{startDate} AND #{endDate}")
    List<IndexDaily> getIndexDailiesByTsCodeAndDateRange(String tsCode, Long startDate, Long endDate);
}

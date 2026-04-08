package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockMoneyflow;

import java.util.List;

@Mapper
public interface StockMoneyflowDao {

    @Insert("INSERT INTO alphafrog_stock_moneyflow (ts_code, trade_date, buy_sm_vol, buy_sm_amount, " +
            "sell_sm_vol, sell_sm_amount, buy_md_vol, buy_md_amount, sell_md_vol, sell_md_amount, " +
            "buy_lg_vol, buy_lg_amount, sell_lg_vol, sell_lg_amount, buy_elg_vol, buy_elg_amount, " +
            "sell_elg_vol, sell_elg_amount, net_mf_vol, net_mf_amount) " +
            "VALUES (#{tsCode}, #{tradeDate}, #{buySmVol}, #{buySmAmount}, " +
            "#{sellSmVol}, #{sellSmAmount}, #{buyMdVol}, #{buyMdAmount}, #{sellMdVol}, #{sellMdAmount}, " +
            "#{buyLgVol}, #{buyLgAmount}, #{sellLgVol}, #{sellLgAmount}, #{buyElgVol}, #{buyElgAmount}, " +
            "#{sellElgVol}, #{sellElgAmount}, #{netMfVol}, #{netMfAmount}) " +
            "ON CONFLICT (ts_code, trade_date) DO NOTHING")
    int insertStockMoneyflow(StockMoneyflow stockMoneyflow);

    @Select("SELECT * FROM alphafrog_stock_moneyflow WHERE ts_code = #{tsCode} " +
            "AND trade_date BETWEEN #{startDate} AND #{endDate}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "tradeDate", column = "trade_date"),
            @Result(property = "buySmVol", column = "buy_sm_vol"),
            @Result(property = "buySmAmount", column = "buy_sm_amount"),
            @Result(property = "sellSmVol", column = "sell_sm_vol"),
            @Result(property = "sellSmAmount", column = "sell_sm_amount"),
            @Result(property = "buyMdVol", column = "buy_md_vol"),
            @Result(property = "buyMdAmount", column = "buy_md_amount"),
            @Result(property = "sellMdVol", column = "sell_md_vol"),
            @Result(property = "sellMdAmount", column = "sell_md_amount"),
            @Result(property = "buyLgVol", column = "buy_lg_vol"),
            @Result(property = "buyLgAmount", column = "buy_lg_amount"),
            @Result(property = "sellLgVol", column = "sell_lg_vol"),
            @Result(property = "sellLgAmount", column = "sell_lg_amount"),
            @Result(property = "buyElgVol", column = "buy_elg_vol"),
            @Result(property = "buyElgAmount", column = "buy_elg_amount"),
            @Result(property = "sellElgVol", column = "sell_elg_vol"),
            @Result(property = "sellElgAmount", column = "sell_elg_amount"),
            @Result(property = "netMfVol", column = "net_mf_vol"),
            @Result(property = "netMfAmount", column = "net_mf_amount")
    })
    List<StockMoneyflow> getByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                                  @Param("startDate") long startDate,
                                                  @Param("endDate") long endDate);

    @Delete("DELETE FROM alphafrog_stock_moneyflow WHERE ts_code = #{tsCode}")
    int deleteByTsCode(@Param("tsCode") String tsCode);

    @Delete("DELETE FROM alphafrog_stock_moneyflow WHERE trade_date BETWEEN #{startDate} AND #{endDate}")
    int deleteByDateRange(@Param("startDate") long startDate, @Param("endDate") long endDate);
}

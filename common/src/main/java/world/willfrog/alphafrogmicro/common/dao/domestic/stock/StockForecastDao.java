package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockForecast;

import java.util.List;

@Mapper
public interface StockForecastDao {

    @Insert("INSERT INTO alphafrog_stock_forecast (ts_code, ann_date, end_date, type, p_change_min, " +
            "p_change_max, net_profit_min, net_profit_max, last_parent_net, first_ann_date, summary, change_reason) " +
            "VALUES (#{tsCode}, #{annDate}, #{endDate}, #{type}, #{pChangeMin}, " +
            "#{pChangeMax}, #{netProfitMin}, #{netProfitMax}, #{lastParentNet}, #{firstAnnDate}, #{summary}, #{changeReason}) " +
            "ON CONFLICT (ts_code, ann_date, end_date) DO NOTHING")
    int insertStockForecast(StockForecast stockForecast);

    @Select("SELECT * FROM alphafrog_stock_forecast WHERE ts_code = #{tsCode}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "annDate", column = "ann_date"),
            @Result(property = "endDate", column = "end_date"),
            @Result(property = "type", column = "type"),
            @Result(property = "pChangeMin", column = "p_change_min"),
            @Result(property = "pChangeMax", column = "p_change_max"),
            @Result(property = "netProfitMin", column = "net_profit_min"),
            @Result(property = "netProfitMax", column = "net_profit_max"),
            @Result(property = "lastParentNet", column = "last_parent_net"),
            @Result(property = "firstAnnDate", column = "first_ann_date"),
            @Result(property = "summary", column = "summary"),
            @Result(property = "changeReason", column = "change_reason")
    })
    List<StockForecast> getByTsCode(@Param("tsCode") String tsCode);

    @Select("SELECT * FROM alphafrog_stock_forecast WHERE ann_date BETWEEN #{startDate} AND #{endDate}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "annDate", column = "ann_date"),
            @Result(property = "endDate", column = "end_date"),
            @Result(property = "type", column = "type"),
            @Result(property = "pChangeMin", column = "p_change_min"),
            @Result(property = "pChangeMax", column = "p_change_max"),
            @Result(property = "netProfitMin", column = "net_profit_min"),
            @Result(property = "netProfitMax", column = "net_profit_max"),
            @Result(property = "lastParentNet", column = "last_parent_net"),
            @Result(property = "firstAnnDate", column = "first_ann_date"),
            @Result(property = "summary", column = "summary"),
            @Result(property = "changeReason", column = "change_reason")
    })
    List<StockForecast> getByAnnDateRange(@Param("startDate") long startDate, @Param("endDate") long endDate);

    @Delete("DELETE FROM alphafrog_stock_forecast WHERE ts_code = #{tsCode}")
    int deleteByTsCode(@Param("tsCode") String tsCode);
}

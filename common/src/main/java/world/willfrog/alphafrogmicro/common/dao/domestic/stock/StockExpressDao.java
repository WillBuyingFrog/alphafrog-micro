package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockExpress;

import java.util.List;

@Mapper
public interface StockExpressDao {

    @Insert("INSERT INTO alphafrog_stock_express (ts_code, ann_date, end_date, revenue, operate_profit, " +
            "total_profit, n_income, total_assets, total_hldr_eqy_exc_min_int, diluted_eps, diluted_roe, " +
            "yoy_net_profit, bps, yoy_sales, yoy_op, yoy_tp, yoy_dedu_np, yoy_eps, yoy_roe, " +
            "perf_summary, is_audit, remark) " +
            "VALUES (#{tsCode}, #{annDate}, #{endDate}, #{revenue}, #{operateProfit}, " +
            "#{totalProfit}, #{nIncome}, #{totalAssets}, #{totalHldrEqyExcMinInt}, #{dilutedEps}, #{dilutedRoe}, " +
            "#{yoyNetProfit}, #{bps}, #{yoySales}, #{yoyOp}, #{yoyTp}, #{yoyDeduNp}, #{yoyEps}, #{yoyRoe}, " +
            "#{perfSummary}, #{isAudit}, #{remark}) " +
            "ON CONFLICT (ts_code, ann_date, end_date) DO NOTHING")
    int insertStockExpress(StockExpress stockExpress);

    @Select("SELECT * FROM alphafrog_stock_express WHERE ts_code = #{tsCode}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "annDate", column = "ann_date"),
            @Result(property = "endDate", column = "end_date"),
            @Result(property = "revenue", column = "revenue"),
            @Result(property = "operateProfit", column = "operate_profit"),
            @Result(property = "totalProfit", column = "total_profit"),
            @Result(property = "nIncome", column = "n_income"),
            @Result(property = "totalAssets", column = "total_assets"),
            @Result(property = "totalHldrEqyExcMinInt", column = "total_hldr_eqy_exc_min_int"),
            @Result(property = "dilutedEps", column = "diluted_eps"),
            @Result(property = "dilutedRoe", column = "diluted_roe"),
            @Result(property = "yoyNetProfit", column = "yoy_net_profit"),
            @Result(property = "bps", column = "bps"),
            @Result(property = "yoySales", column = "yoy_sales"),
            @Result(property = "yoyOp", column = "yoy_op"),
            @Result(property = "yoyTp", column = "yoy_tp"),
            @Result(property = "yoyDeduNp", column = "yoy_dedu_np"),
            @Result(property = "yoyEps", column = "yoy_eps"),
            @Result(property = "yoyRoe", column = "yoy_roe"),
            @Result(property = "perfSummary", column = "perf_summary"),
            @Result(property = "isAudit", column = "is_audit"),
            @Result(property = "remark", column = "remark")
    })
    List<StockExpress> getByTsCode(@Param("tsCode") String tsCode);

    @Delete("DELETE FROM alphafrog_stock_express WHERE ts_code = #{tsCode}")
    int deleteByTsCode(@Param("tsCode") String tsCode);
}

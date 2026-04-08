package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockCashflow;

import java.util.List;

@Mapper
public interface StockCashflowDao {

    @Insert("INSERT INTO alphafrog_stock_cashflow (ts_code, ann_date, f_ann_date, end_date, comp_type, " +
            "report_type, end_type, c_fr_sale_sg, n_cashflow_act, n_cashflow_inv_act, " +
            "n_cash_flows_fnc_act, free_cashflow, c_cash_equ_end_period, n_incr_cash_cash_equ, update_flag, extended) " +
            "VALUES (#{tsCode}, #{annDate}, #{fAnnDate}, #{endDate}, #{compType}, " +
            "#{reportType}, #{endType}, #{cFrSaleSg}, #{nCashflowAct}, #{nCashflowInvAct}, " +
            "#{nCashFlowsFncAct}, #{freeCashflow}, #{cCashEquEndPeriod}, #{nIncrCashCashEqu}, #{updateFlag}, #{extended}::jsonb) " +
            "ON CONFLICT (ts_code, end_date, report_type) DO NOTHING")
    int insertStockCashflow(StockCashflow stockCashflow);

    @Select("SELECT * FROM alphafrog_stock_cashflow WHERE ts_code = #{tsCode} " +
            "AND end_date BETWEEN #{startDate} AND #{endDate}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "annDate", column = "ann_date"),
            @Result(property = "fAnnDate", column = "f_ann_date"),
            @Result(property = "endDate", column = "end_date"),
            @Result(property = "compType", column = "comp_type"),
            @Result(property = "reportType", column = "report_type"),
            @Result(property = "endType", column = "end_type"),
            @Result(property = "cFrSaleSg", column = "c_fr_sale_sg"),
            @Result(property = "nCashflowAct", column = "n_cashflow_act"),
            @Result(property = "nCashflowInvAct", column = "n_cashflow_inv_act"),
            @Result(property = "nCashFlowsFncAct", column = "n_cash_flows_fnc_act"),
            @Result(property = "freeCashflow", column = "free_cashflow"),
            @Result(property = "cCashEquEndPeriod", column = "c_cash_equ_end_period"),
            @Result(property = "nIncrCashCashEqu", column = "n_incr_cash_cash_equ"),
            @Result(property = "updateFlag", column = "update_flag"),
            @Result(property = "extended", column = "extended")
    })
    List<StockCashflow> getByTsCodeAndEndDateRange(@Param("tsCode") String tsCode,
                                                    @Param("startDate") long startDate,
                                                    @Param("endDate") long endDate);

    @Delete("DELETE FROM alphafrog_stock_cashflow WHERE ts_code = #{tsCode}")
    int deleteByTsCode(@Param("tsCode") String tsCode);

    @Delete("DELETE FROM alphafrog_stock_cashflow WHERE end_date = #{endDate}")
    int deleteByEndDate(@Param("endDate") long endDate);
}

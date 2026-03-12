package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockIncome;

import java.util.List;

@Mapper
public interface StockIncomeDao {

    @Insert("INSERT INTO alphafrog_stock_income (ts_code, ann_date, f_ann_date, end_date, report_type, " +
            "comp_type, end_type, basic_eps, diluted_eps, total_revenue, revenue, total_cogs, " +
            "operate_profit, total_profit, n_income, n_income_attr_p, ebit, ebitda, rd_exp, " +
            "update_flag, extended) " +
            "VALUES (#{tsCode}, #{annDate}, #{fAnnDate}, #{endDate}, #{reportType}, " +
            "#{compType}, #{endType}, #{basicEps}, #{dilutedEps}, #{totalRevenue}, #{revenue}, #{totalCogs}, " +
            "#{operateProfit}, #{totalProfit}, #{nIncome}, #{nIncomeAttrP}, #{ebit}, #{ebitda}, #{rdExp}, " +
            "#{updateFlag}, #{extended}) " +
            "ON CONFLICT (ts_code, end_date, report_type) DO NOTHING")
    int insertStockIncome(StockIncome stockIncome);

    @Select("SELECT * FROM alphafrog_stock_income WHERE ts_code = #{tsCode} " +
            "AND end_date BETWEEN #{startDate} AND #{endDate}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "annDate", column = "ann_date"),
            @Result(property = "fAnnDate", column = "f_ann_date"),
            @Result(property = "endDate", column = "end_date"),
            @Result(property = "reportType", column = "report_type"),
            @Result(property = "compType", column = "comp_type"),
            @Result(property = "endType", column = "end_type"),
            @Result(property = "basicEps", column = "basic_eps"),
            @Result(property = "dilutedEps", column = "diluted_eps"),
            @Result(property = "totalRevenue", column = "total_revenue"),
            @Result(property = "revenue", column = "revenue"),
            @Result(property = "totalCogs", column = "total_cogs"),
            @Result(property = "operateProfit", column = "operate_profit"),
            @Result(property = "totalProfit", column = "total_profit"),
            @Result(property = "nIncome", column = "n_income"),
            @Result(property = "nIncomeAttrP", column = "n_income_attr_p"),
            @Result(property = "ebit", column = "ebit"),
            @Result(property = "ebitda", column = "ebitda"),
            @Result(property = "rdExp", column = "rd_exp"),
            @Result(property = "updateFlag", column = "update_flag"),
            @Result(property = "extended", column = "extended")
    })
    List<StockIncome> getByTsCodeAndEndDateRange(@Param("tsCode") String tsCode,
                                                  @Param("startDate") long startDate,
                                                  @Param("endDate") long endDate);

    @Delete("DELETE FROM alphafrog_stock_income WHERE ts_code = #{tsCode}")
    int deleteByTsCode(@Param("tsCode") String tsCode);

    @Delete("DELETE FROM alphafrog_stock_income WHERE end_date = #{endDate}")
    int deleteByEndDate(@Param("endDate") long endDate);
}

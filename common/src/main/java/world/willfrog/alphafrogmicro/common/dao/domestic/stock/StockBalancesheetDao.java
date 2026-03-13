package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockBalancesheet;

import java.util.List;

@Mapper
public interface StockBalancesheetDao {

    @Insert("INSERT INTO alphafrog_stock_balancesheet (ts_code, ann_date, f_ann_date, end_date, report_type, " +
            "comp_type, end_type, money_cap, accounts_receiv, inventories, total_cur_assets, " +
            "fix_assets, goodwill, intan_assets, r_and_d, total_nca, total_assets, " +
            "st_borr, acct_payable, total_cur_liab, lt_borr, bond_payable, total_ncl, total_liab, " +
            "total_hldr_eqy_exc_min_int, total_hldr_eqy_inc_min_int, minority_int, update_flag, extended) " +
            "VALUES (#{tsCode}, #{annDate}, #{fAnnDate}, #{endDate}, #{reportType}, " +
            "#{compType}, #{endType}, #{moneyCap}, #{accountsReceiv}, #{inventories}, #{totalCurAssets}, " +
            "#{fixAssets}, #{goodwill}, #{intanAssets}, #{rAndD}, #{totalNca}, #{totalAssets}, " +
            "#{stBorr}, #{acctPayable}, #{totalCurLiab}, #{ltBorr}, #{bondPayable}, #{totalNcl}, #{totalLiab}, " +
            "#{totalHldrEqyExcMinInt}, #{totalHldrEqyIncMinInt}, #{minorityInt}, #{updateFlag}, #{extended}::jsonb) " +
            "ON CONFLICT (ts_code, end_date, report_type) DO NOTHING")
    int insertStockBalancesheet(StockBalancesheet stockBalancesheet);

    @Select("SELECT * FROM alphafrog_stock_balancesheet WHERE ts_code = #{tsCode} " +
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
            @Result(property = "moneyCap", column = "money_cap"),
            @Result(property = "accountsReceiv", column = "accounts_receiv"),
            @Result(property = "inventories", column = "inventories"),
            @Result(property = "totalCurAssets", column = "total_cur_assets"),
            @Result(property = "fixAssets", column = "fix_assets"),
            @Result(property = "goodwill", column = "goodwill"),
            @Result(property = "intanAssets", column = "intan_assets"),
            @Result(property = "rAndD", column = "r_and_d"),
            @Result(property = "totalNca", column = "total_nca"),
            @Result(property = "totalAssets", column = "total_assets"),
            @Result(property = "stBorr", column = "st_borr"),
            @Result(property = "acctPayable", column = "acct_payable"),
            @Result(property = "totalCurLiab", column = "total_cur_liab"),
            @Result(property = "ltBorr", column = "lt_borr"),
            @Result(property = "bondPayable", column = "bond_payable"),
            @Result(property = "totalNcl", column = "total_ncl"),
            @Result(property = "totalLiab", column = "total_liab"),
            @Result(property = "totalHldrEqyExcMinInt", column = "total_hldr_eqy_exc_min_int"),
            @Result(property = "totalHldrEqyIncMinInt", column = "total_hldr_eqy_inc_min_int"),
            @Result(property = "minorityInt", column = "minority_int"),
            @Result(property = "updateFlag", column = "update_flag"),
            @Result(property = "extended", column = "extended")
    })
    List<StockBalancesheet> getByTsCodeAndEndDateRange(@Param("tsCode") String tsCode,
                                                        @Param("startDate") long startDate,
                                                        @Param("endDate") long endDate);

    @Delete("DELETE FROM alphafrog_stock_balancesheet WHERE ts_code = #{tsCode}")
    int deleteByTsCode(@Param("tsCode") String tsCode);

    @Delete("DELETE FROM alphafrog_stock_balancesheet WHERE end_date = #{endDate}")
    int deleteByEndDate(@Param("endDate") long endDate);
}

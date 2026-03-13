package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockReportRc;

import java.util.List;

@Mapper
public interface StockReportRcDao {

    @Insert("INSERT INTO alphafrog_stock_report_rc (ts_code, name, report_date, report_title, report_type, " +
            "classify, org_name, author_name, quarter, op_rt, op_pr, tp, np, eps, pe, rd, roe, " +
            "ev_ebitda, rating, max_price, min_price) " +
            "VALUES (#{tsCode}, #{name}, #{reportDate}, #{reportTitle}, #{reportType}, " +
            "#{classify}, #{orgName}, #{authorName}, #{quarter}, #{opRt}, #{opPr}, #{tp}, #{np}, #{eps}, #{pe}, #{rd}, #{roe}, " +
            "#{evEbitda}, #{rating}, #{maxPrice}, #{minPrice}) " +
            "ON CONFLICT (ts_code, report_date, org_name, quarter) DO NOTHING")
    int insertStockReportRc(StockReportRc stockReportRc);

    @Select("SELECT * FROM alphafrog_stock_report_rc WHERE ts_code = #{tsCode}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "name", column = "name"),
            @Result(property = "reportDate", column = "report_date"),
            @Result(property = "reportTitle", column = "report_title"),
            @Result(property = "reportType", column = "report_type"),
            @Result(property = "classify", column = "classify"),
            @Result(property = "orgName", column = "org_name"),
            @Result(property = "authorName", column = "author_name"),
            @Result(property = "quarter", column = "quarter"),
            @Result(property = "opRt", column = "op_rt"),
            @Result(property = "opPr", column = "op_pr"),
            @Result(property = "tp", column = "tp"),
            @Result(property = "np", column = "np"),
            @Result(property = "eps", column = "eps"),
            @Result(property = "pe", column = "pe"),
            @Result(property = "rd", column = "rd"),
            @Result(property = "roe", column = "roe"),
            @Result(property = "evEbitda", column = "ev_ebitda"),
            @Result(property = "rating", column = "rating"),
            @Result(property = "maxPrice", column = "max_price"),
            @Result(property = "minPrice", column = "min_price")
    })
    List<StockReportRc> getByTsCode(@Param("tsCode") String tsCode);

    @Select("SELECT * FROM alphafrog_stock_report_rc WHERE report_date BETWEEN #{startDate} AND #{endDate}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "name", column = "name"),
            @Result(property = "reportDate", column = "report_date"),
            @Result(property = "reportTitle", column = "report_title"),
            @Result(property = "reportType", column = "report_type"),
            @Result(property = "classify", column = "classify"),
            @Result(property = "orgName", column = "org_name"),
            @Result(property = "authorName", column = "author_name"),
            @Result(property = "quarter", column = "quarter"),
            @Result(property = "opRt", column = "op_rt"),
            @Result(property = "opPr", column = "op_pr"),
            @Result(property = "tp", column = "tp"),
            @Result(property = "np", column = "np"),
            @Result(property = "eps", column = "eps"),
            @Result(property = "pe", column = "pe"),
            @Result(property = "rd", column = "rd"),
            @Result(property = "roe", column = "roe"),
            @Result(property = "evEbitda", column = "ev_ebitda"),
            @Result(property = "rating", column = "rating"),
            @Result(property = "maxPrice", column = "max_price"),
            @Result(property = "minPrice", column = "min_price")
    })
    List<StockReportRc> getByReportDateRange(@Param("startDate") long startDate, @Param("endDate") long endDate);

    @Delete("DELETE FROM alphafrog_stock_report_rc WHERE ts_code = #{tsCode}")
    int deleteByTsCode(@Param("tsCode") String tsCode);
}

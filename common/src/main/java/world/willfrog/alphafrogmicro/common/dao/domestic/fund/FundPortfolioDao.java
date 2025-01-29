package world.willfrog.alphafrogmicro.common.dao.domestic.fund;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundPortfolio;

import java.util.List;

@Mapper
public interface FundPortfolioDao {

    @Insert("INSERT INTO alphafrog_fund_portfolio (ts_code, ann_date, end_date, symbol, mkv, amount, stk_mkv_ratio, stk_float_ratio) " +
            "VALUES (#{tsCode}, #{annDate}, #{endDate}, #{symbol}, #{mkv}, #{amount}, #{stkMkvRatio}, #{stkFloatRatio}) " +
            "ON CONFLICT (ts_code, symbol, ann_date) DO NOTHING")
    int insertFundPortfolio(FundPortfolio fundPortfolio);

    @Select("SELECT * FROM alphafrog_fund_portfolio WHERE ts_code = #{tsCode} AND" +
            " ann_date BETWEEN #{startDateTimestamp} AND #{endDateTimestamp}")
    List<FundPortfolio> getFundPortfolioByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                                             @Param("startDateTimestamp") long startDateTimestamp,
                                                             @Param("endDateTimestamp") long endDateTimestamp);


}
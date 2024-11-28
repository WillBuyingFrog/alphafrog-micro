package world.willfrog.alphafrogmicro.common.dao.domestic.fund;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundNav;

import java.util.List;

@Mapper
public interface FundNavDao {

    @Insert("insert into alphafrog_fund_nav(ts_code, ann_date, nav_date, unit_nav, accum_nav, accum_div, net_asset, total_net_asset, adj_nav) " +
            "values(#{tsCode}, #{annDate}, #{navDate}, #{unitNav}, #{accumNav}, #{accumDiv}, #{netAsset}, #{totalNetAsset}, #{adjNav})" +
            "ON CONFLICT do nothing")
    int insertFundNav(FundNav fundNav);

    @Select("select * from alphafrog_fund_nav where ts_code = #{tsCode} " +
            "and nav_date >= #{startDateTimestamp} and nav_date <= #{endDateTimestamp}")
    List<FundNav> getFundNavsByTsCodeAndDateRange(String tsCode, long startDateTimestamp, long endDateTimestamp);

}
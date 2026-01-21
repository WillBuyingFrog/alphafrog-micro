package world.willfrog.alphafrogmicro.common.dao.domestic.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DataOverviewDao {

    @Select("SELECT COUNT(*) FROM alphafrog_fund_info")
    long countFundInfo();

    @Select("SELECT COUNT(*) FROM alphafrog_index_info")
    long countIndexInfo();

    @Select("SELECT COUNT(*) FROM alphafrog_stock_info")
    long countStockInfo();

    @Select("SELECT COUNT(*) FROM alphafrog_fund_nav")
    long countFundNav();

    @Select("SELECT COUNT(*) FROM alphafrog_index_daily")
    long countIndexDaily();

    @Select("SELECT COUNT(*) FROM alphafrog_stock_daily")
    long countStockDaily();
}

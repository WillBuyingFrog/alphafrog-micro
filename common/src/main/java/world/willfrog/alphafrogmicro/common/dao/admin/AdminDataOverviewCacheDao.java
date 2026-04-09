package world.willfrog.alphafrogmicro.common.dao.admin;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.admin.AdminDataOverviewCache;

import java.time.OffsetDateTime;

@Mapper
public interface AdminDataOverviewCacheDao {

    @Insert("INSERT INTO alphafrog_admin_data_overview_cache (" +
            "fund_count, index_count, stock_count, fund_nav_count, index_daily_count, stock_daily_count, " +
            "cached_at, created_at, updated_at" +
            ") VALUES (" +
            "#{fundCount}, #{indexCount}, #{stockCount}, #{fundNavCount}, #{indexDailyCount}, #{stockDailyCount}, " +
            "#{cachedAt}, #{createdAt}, #{updatedAt}" +
            ")")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AdminDataOverviewCache cache);

    @Select("SELECT * FROM alphafrog_admin_data_overview_cache ORDER BY cached_at DESC LIMIT 1")
    @Results(id = "adminDataOverviewCacheResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "fundCount", column = "fund_count"),
            @Result(property = "indexCount", column = "index_count"),
            @Result(property = "stockCount", column = "stock_count"),
            @Result(property = "fundNavCount", column = "fund_nav_count"),
            @Result(property = "indexDailyCount", column = "index_daily_count"),
            @Result(property = "stockDailyCount", column = "stock_daily_count"),
            @Result(property = "cachedAt", column = "cached_at"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at")
    })
    AdminDataOverviewCache getLatest();

    @Update("UPDATE alphafrog_admin_data_overview_cache SET " +
            "fund_count = #{fundCount}, index_count = #{indexCount}, stock_count = #{stockCount}, " +
            "fund_nav_count = #{fundNavCount}, index_daily_count = #{indexDailyCount}, stock_daily_count = #{stockDailyCount}, " +
            "cached_at = #{cachedAt}, updated_at = #{updatedAt} " +
            "WHERE id = #{id}")
    int updateById(AdminDataOverviewCache cache);

    @Delete("DELETE FROM alphafrog_admin_data_overview_cache WHERE id != (SELECT MAX(id) FROM alphafrog_admin_data_overview_cache)")
    int deleteOldRecords();
}

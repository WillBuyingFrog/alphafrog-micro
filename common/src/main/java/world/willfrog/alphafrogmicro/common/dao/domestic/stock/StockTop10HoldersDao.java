package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockTop10Holders;

import java.util.List;

@Mapper
public interface StockTop10HoldersDao {

    @Insert("INSERT INTO alphafrog_stock_top10_holders (ts_code, ann_date, end_date, holder_name, " +
            "hold_amount, hold_ratio, hold_float_ratio, hold_change, holder_type) " +
            "VALUES (#{tsCode}, #{annDate}, #{endDate}, #{holderName}, " +
            "#{holdAmount}, #{holdRatio}, #{holdFloatRatio}, #{holdChange}, #{holderType}) " +
            "ON CONFLICT (ts_code, end_date, holder_name) DO NOTHING")
    int insertStockTop10Holders(StockTop10Holders stockTop10Holders);

    @Select("SELECT * FROM alphafrog_stock_top10_holders WHERE ts_code = #{tsCode}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "annDate", column = "ann_date"),
            @Result(property = "endDate", column = "end_date"),
            @Result(property = "holderName", column = "holder_name"),
            @Result(property = "holdAmount", column = "hold_amount"),
            @Result(property = "holdRatio", column = "hold_ratio"),
            @Result(property = "holdFloatRatio", column = "hold_float_ratio"),
            @Result(property = "holdChange", column = "hold_change"),
            @Result(property = "holderType", column = "holder_type")
    })
    List<StockTop10Holders> getByTsCode(@Param("tsCode") String tsCode);

    @Delete("DELETE FROM alphafrog_stock_top10_holders WHERE ts_code = #{tsCode}")
    int deleteByTsCode(@Param("tsCode") String tsCode);

    @Delete("DELETE FROM alphafrog_stock_top10_holders WHERE end_date = #{endDate}")
    int deleteByEndDate(@Param("endDate") long endDate);
}

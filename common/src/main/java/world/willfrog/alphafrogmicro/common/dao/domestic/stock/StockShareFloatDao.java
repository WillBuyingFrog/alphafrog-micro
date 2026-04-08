package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockShareFloat;

import java.util.List;

@Mapper
public interface StockShareFloatDao {

    @Insert("INSERT INTO alphafrog_stock_share_float (ts_code, ann_date, float_date, float_share, " +
            "float_ratio, holder_name, share_type) " +
            "VALUES (#{tsCode}, #{annDate}, #{floatDate}, #{floatShare}, " +
            "#{floatRatio}, #{holderName}, #{shareType}) " +
            "ON CONFLICT (ts_code, float_date, holder_name, share_type) DO NOTHING")
    int insertStockShareFloat(StockShareFloat stockShareFloat);

    @Select("SELECT * FROM alphafrog_stock_share_float WHERE ts_code = #{tsCode}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "annDate", column = "ann_date"),
            @Result(property = "floatDate", column = "float_date"),
            @Result(property = "floatShare", column = "float_share"),
            @Result(property = "floatRatio", column = "float_ratio"),
            @Result(property = "holderName", column = "holder_name"),
            @Result(property = "shareType", column = "share_type")
    })
    List<StockShareFloat> getByTsCode(@Param("tsCode") String tsCode);

    @Select("SELECT * FROM alphafrog_stock_share_float WHERE float_date BETWEEN #{startDate} AND #{endDate}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "annDate", column = "ann_date"),
            @Result(property = "floatDate", column = "float_date"),
            @Result(property = "floatShare", column = "float_share"),
            @Result(property = "floatRatio", column = "float_ratio"),
            @Result(property = "holderName", column = "holder_name"),
            @Result(property = "shareType", column = "share_type")
    })
    List<StockShareFloat> getByFloatDateRange(@Param("startDate") long startDate, @Param("endDate") long endDate);

    @Delete("DELETE FROM alphafrog_stock_share_float WHERE ts_code = #{tsCode}")
    int deleteByTsCode(@Param("tsCode") String tsCode);
}

package world.willfrog.alphafrogmicro.common.dao.domestic.index;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexWeight;

import java.util.List;

@Mapper
public interface IndexWeightDao {

    @Insert("INSERT INTO alphafrog_index_weight (index_code, con_code, trade_date, weight) " +
            "VALUES (#{indexCode}, #{conCode}, #{tradeDate}, #{weight}) " +
            "ON CONFLICT (index_code, con_code, trade_date) DO NOTHING")
    int insertIndexWeight(IndexWeight indexWeight);


    @Select("SELECT * FROM alphafrog_index_weight WHERE index_code = #{tsCode} AND trade_date BETWEEN #{startDate} AND #{endDate}")
    @Results({
            @Result(property = "indexCode", column = "index_code"),
            @Result(property = "conCode", column = "con_code"),
            @Result(property = "tradeDate", column = "trade_date"),
            @Result(property = "weight", column = "weight")
    })
    List<IndexWeight> getIndexWeightsByTsCodeAndDateRange(@Param("tsCode") String tsCode, @Param("startDate") long startDate, @Param("endDate") long endDate);

    @Select("SELECT * FROM alphafrog_index_weight WHERE con_code = #{conCode} AND trade_date BETWEEN #{startDate} AND #{endDate}")
    List<IndexWeight> getIndexWeightsByConCodeAndDateRange(@Param("conCode") String conCode, @Param("startDate") long startDate, @Param("endDate") long endDate);

}
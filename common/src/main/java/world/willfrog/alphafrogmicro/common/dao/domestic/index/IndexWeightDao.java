package world.willfrog.alphafrogmicro.common.dao.domestic.index;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexWeight;

import java.util.List;

@Mapper
public interface IndexWeightDao {

    @Insert("INSERT INTO alphafrog_index_weight (index_code, con_code, trade_date, weight) " +
            "VALUES (#{indexCode}, #{conCode}, #{tradeDate}, #{weight}) " +
            "ON CONFLICT (index_code, con_code, trade_date) DO NOTHING")
    int insertIndexWeight(IndexWeight indexWeight);


    @Select("SELECT * FROM alphafrog_index_weight WHERE index_code = #{tsCode} AND trade_date BETWEEN #{startDate} AND #{endDate}")
    List<IndexWeight> getIndexWeightsByTsCodeAndDateRange(@Param("tsCode") String tsCode, @Param("startDate") long startDate, @Param("endDate") long endDate);

    @Select("SELECT * FROM alphafrog_index_weight WHERE con_code = #{conCode} AND trade_date BETWEEN #{startDate} AND #{endDate}")
    List<IndexWeight> getIndexWeightsByConCodeAndDateRange(@Param("conCode") String conCode, @Param("startDate") long startDate, @Param("endDate") long endDate);

}
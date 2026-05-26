package world.willfrog.alphafrogmicro.common.dao.domestic.etf;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.etf.EtfAdjFactor;

import java.util.List;

@Mapper
public interface EtfAdjFactorDao {

    @Insert("INSERT INTO alphafrog_domestic_etf_adj_factor (ts_code, trade_date, adj_factor) " +
            "VALUES (#{tsCode}, #{tradeDate}, #{adjFactor}) " +
            "ON CONFLICT (ts_code, trade_date) DO NOTHING")
    int insertEtfAdjFactor(EtfAdjFactor etfAdjFactor);

    @Select("SELECT * FROM alphafrog_domestic_etf_adj_factor WHERE ts_code = #{tsCode} " +
            "AND trade_date BETWEEN #{startDate} AND #{endDate} ORDER BY trade_date")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "tradeDate", column = "trade_date"),
            @Result(property = "adjFactor", column = "adj_factor")
    })
    List<EtfAdjFactor> getByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                              @Param("startDate") long startDate,
                                              @Param("endDate") long endDate);
}

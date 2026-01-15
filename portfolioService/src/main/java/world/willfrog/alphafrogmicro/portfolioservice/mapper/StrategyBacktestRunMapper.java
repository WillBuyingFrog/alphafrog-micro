package world.willfrog.alphafrogmicro.portfolioservice.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.alphafrogmicro.portfolioservice.domain.StrategyBacktestRunPo;

import java.util.List;

@Mapper
public interface StrategyBacktestRunMapper {

    int insert(StrategyBacktestRunPo po);

    StrategyBacktestRunPo findByIdAndUser(@Param("id") Long id, @Param("userId") String userId);

    List<StrategyBacktestRunPo> listByStrategy(@Param("strategyId") Long strategyId,
                                               @Param("userId") String userId,
                                               @Param("status") String status,
                                               @Param("offset") int offset,
                                               @Param("limit") int limit);

    long countByStrategy(@Param("strategyId") Long strategyId,
                         @Param("userId") String userId,
                         @Param("status") String status);

    int update(StrategyBacktestRunPo po);
}

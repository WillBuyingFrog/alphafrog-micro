package world.willfrog.alphafrogmicro.portfolioservice.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.alphafrogmicro.portfolioservice.domain.StrategyTargetPo;

import java.util.List;

@Mapper
public interface StrategyTargetMapper {

    int deleteByStrategyId(@Param("strategyId") Long strategyId, @Param("userId") String userId);

    int insertBatch(@Param("list") List<StrategyTargetPo> list);

    List<StrategyTargetPo> listByStrategy(@Param("strategyId") Long strategyId, @Param("userId") String userId);
}

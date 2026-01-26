package world.willfrog.alphafrogmicro.portfolioservice.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.alphafrogmicro.portfolioservice.domain.StrategyDefinitionPo;

import java.util.List;

@Mapper
public interface StrategyDefinitionMapper {

    int insert(StrategyDefinitionPo po);

    StrategyDefinitionPo findByIdAndUser(@Param("id") Long id, @Param("userId") String userId);

    List<StrategyDefinitionPo> list(@Param("userId") String userId,
                                    @Param("status") String status,
                                    @Param("keyword") String keyword,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    long count(@Param("userId") String userId,
               @Param("status") String status,
               @Param("keyword") String keyword);

    int update(StrategyDefinitionPo po);

    int archive(@Param("id") Long id, @Param("userId") String userId);
}

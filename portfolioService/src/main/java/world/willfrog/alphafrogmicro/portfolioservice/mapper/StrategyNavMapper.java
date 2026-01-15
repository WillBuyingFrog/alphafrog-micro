package world.willfrog.alphafrogmicro.portfolioservice.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.alphafrogmicro.portfolioservice.domain.StrategyNavPo;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface StrategyNavMapper {

    int insertBatch(@Param("list") List<StrategyNavPo> list);

    List<StrategyNavPo> listByRun(@Param("runId") Long runId,
                                  @Param("userId") String userId,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    long countByRun(@Param("runId") Long runId,
                    @Param("userId") String userId,
                    @Param("from") LocalDate from,
                    @Param("to") LocalDate to);
}

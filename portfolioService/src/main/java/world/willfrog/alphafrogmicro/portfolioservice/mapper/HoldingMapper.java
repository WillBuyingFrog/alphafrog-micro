package world.willfrog.alphafrogmicro.portfolioservice.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.alphafrogmicro.portfolioservice.domain.PortfolioHoldingPo;

import java.util.List;

@Mapper
public interface HoldingMapper {

    int deleteByPortfolioId(@Param("portfolioId") Long portfolioId, @Param("userId") String userId);

    int insertBatch(@Param("list") List<PortfolioHoldingPo> list);

    List<PortfolioHoldingPo> listByPortfolio(@Param("portfolioId") Long portfolioId, @Param("userId") String userId);
}

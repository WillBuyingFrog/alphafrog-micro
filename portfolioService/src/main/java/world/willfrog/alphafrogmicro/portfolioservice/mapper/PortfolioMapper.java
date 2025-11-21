package world.willfrog.alphafrogmicro.portfolioservice.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.alphafrogmicro.portfolioservice.domain.PortfolioPo;

import java.util.List;

@Mapper
public interface PortfolioMapper {

    int insert(PortfolioPo po);

    PortfolioPo findByIdAndUser(@Param("id") Long id, @Param("userId") String userId);

    List<PortfolioPo> list(@Param("userId") String userId,
                           @Param("status") String status,
                           @Param("keyword") String keyword,
                           @Param("offset") int offset,
                           @Param("limit") int limit);

    long count(@Param("userId") String userId,
               @Param("status") String status,
               @Param("keyword") String keyword);

    long countActiveName(@Param("userId") String userId, @Param("name") String name);
}

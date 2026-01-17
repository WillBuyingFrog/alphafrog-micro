package world.willfrog.alphafrogmicro.portfolioservice.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.alphafrogmicro.portfolioservice.domain.PricePoint;

import java.util.List;

@Mapper
public interface StrategyPriceMapper {

    List<PricePoint> listStockDaily(@Param("tsCode") String tsCode,
                                    @Param("startDate") long startDate,
                                    @Param("endDate") long endDate);

    List<PricePoint> listIndexDaily(@Param("tsCode") String tsCode,
                                    @Param("startDate") long startDate,
                                    @Param("endDate") long endDate);

    List<PricePoint> listFundNav(@Param("tsCode") String tsCode,
                                 @Param("startDate") long startDate,
                                 @Param("endDate") long endDate);
}

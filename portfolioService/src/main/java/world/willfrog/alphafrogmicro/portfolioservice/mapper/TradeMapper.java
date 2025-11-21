package world.willfrog.alphafrogmicro.portfolioservice.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.alphafrogmicro.portfolioservice.domain.PortfolioTradePo;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface TradeMapper {

    int insertBatch(@Param("list") List<PortfolioTradePo> list);

    List<PortfolioTradePo> list(
            @Param("portfolioId") Long portfolioId,
            @Param("userId") String userId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            @Param("eventType") String eventType,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    long count(
            @Param("portfolioId") Long portfolioId,
            @Param("userId") String userId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            @Param("eventType") String eventType
    );
}

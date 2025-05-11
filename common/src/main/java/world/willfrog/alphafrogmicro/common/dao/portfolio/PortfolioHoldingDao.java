package world.willfrog.alphafrogmicro.common.dao.portfolio;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.PortfolioHolding;

import java.util.List;

@Mapper
public interface PortfolioHoldingDao {

    @Insert("INSERT INTO portfolio_holding (portfolio_id, asset_identifier, asset_type, quantity, average_cost_price) " +
            "VALUES (#{portfolio.id}, #{assetIdentifier}, #{assetType}, #{quantity}, #{averageCostPrice})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(PortfolioHolding holding);

    @Select("SELECT id, portfolio_id, asset_identifier, asset_type, quantity, average_cost_price " +
            "FROM portfolio_holding WHERE id = #{id}")
    // You might need a ResultMap here if portfolio_id needs to be mapped to a Portfolio object directly by MyBatis
    PortfolioHolding findById(Long id);

    @Select("SELECT id, portfolio_id, asset_identifier, asset_type, quantity, average_cost_price " +
            "FROM portfolio_holding WHERE portfolio_id = #{portfolioId}")
    List<PortfolioHolding> findByPortfolioId(Long portfolioId);

    @Update("UPDATE portfolio_holding SET asset_identifier = #{assetIdentifier}, asset_type = #{assetType}, " +
            "quantity = #{quantity}, average_cost_price = #{averageCostPrice} " +
            "WHERE id = #{id}")
    int update(PortfolioHolding holding);

    @Delete("DELETE FROM portfolio_holding WHERE id = #{id}")
    int deleteById(Long id);

    @Delete("DELETE FROM portfolio_holding WHERE portfolio_id = #{portfolioId}")
    int deleteByPortfolioId(Long portfolioId);

} 
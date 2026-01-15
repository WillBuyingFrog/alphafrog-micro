package world.willfrog.alphafrogmicro.common.dao.portfolio;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.Portfolio;

import java.util.List;

@Mapper
public interface PortfolioDao {

    @Insert("INSERT INTO portfolio (user_id, name) VALUES (#{userId}, #{name})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(Portfolio portfolio);

    @Select("SELECT id, user_id, name FROM portfolio WHERE id = #{id}")
    Portfolio findById(Long id);

    @Select("SELECT id, user_id, name FROM portfolio WHERE user_id = #{userId}")
    List<Portfolio> findByUserId(String userId);

    @Select("SELECT id, user_id, name FROM portfolio")
    List<Portfolio> findAll();

    @Update("UPDATE portfolio SET user_id = #{userId}, name = #{name} WHERE id = #{id}")
    int update(Portfolio portfolio);

    @Delete("DELETE FROM portfolio WHERE id = #{id}")
    int deleteById(Long id);

    // Note: For fetching Portfolio with its holdings, a more complex query or a separate method might be needed
    // to handle the OneToMany relationship, possibly using a JOIN and a resultMap if not relying on lazy loading
    // or separate queries handled by the service layer.
} 
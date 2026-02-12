package world.willfrog.alphafrogmicro.common.dao.user;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.user.User;

import java.util.List;

@Mapper
public interface UserDao {


    @Insert("INSERT INTO alphafrog_user (username, password, email, register_time, user_type, user_level, credit) " +
            "VALUES (#{username}, #{password}, #{email}, #{registerTime}, #{userType}, #{userLevel}, #{credit}) " +
            "ON CONFLICT (user_id) DO NOTHING")
    int insertUser(User user);

    @Select("SELECT * " +
            "FROM alphafrog_user " +
            "WHERE username = #{username} " +
            "LIMIT 1")
    @Results(id = "userResultMap", value = {
            @Result(column = "user_id", property = "userId"),
            @Result(column = "username", property = "username"),
            @Result(column = "password", property = "password"),
            @Result(column = "email", property = "email"),
            @Result(column = "register_time", property = "registerTime"),
            @Result(column = "user_type", property = "userType"),
            @Result(column = "user_level", property = "userLevel"),
            @Result(column = "credit", property = "credit")
    })
    List<User> getUserByUsername(@Param("username") String username);

    @Select("SELECT * " +
            "FROM alphafrog_user " +
            "WHERE user_id = #{userId} " +
            "LIMIT 1")
    @ResultMap("userResultMap")
    User getUserById(@Param("userId") Long userId);

    @Update("UPDATE alphafrog_user " +
            "SET credit = COALESCE(credit, 0) + #{delta} " +
            "WHERE user_id = #{userId}")
    int increaseCreditByUserId(@Param("userId") Long userId, @Param("delta") int delta);

    @Delete("DELETE FROM alphafrog_user WHERE username = #{username} AND user_type = #{userType}")
    int deleteUserByUsernameAndType(@Param("username") String username, @Param("userType") int userType);

}

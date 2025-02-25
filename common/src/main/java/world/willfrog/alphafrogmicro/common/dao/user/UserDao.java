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
    @Results({
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

}

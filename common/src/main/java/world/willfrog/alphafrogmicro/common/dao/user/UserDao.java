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
            @Result(column = "credit", property = "credit"),
            @Result(column = "status", property = "status"),
            @Result(column = "disabled_at", property = "disabledAt"),
            @Result(column = "disabled_reason", property = "disabledReason"),
            @Result(column = "status_updated_at", property = "statusUpdatedAt")
    })
    List<User> getUserByUsername(@Param("username") String username);

    @Select("SELECT * " +
            "FROM alphafrog_user " +
            "WHERE user_id = #{userId} " +
            "LIMIT 1")
    @ResultMap("userResultMap")
    User getUserById(@Param("userId") Long userId);

    @Select("SELECT * " +
            "FROM alphafrog_user " +
            "WHERE email = #{email} " +
            "LIMIT 1")
    @ResultMap("userResultMap")
    List<User> getUserByEmail(@Param("email") String email);

    @Update("UPDATE alphafrog_user " +
            "SET credit = COALESCE(credit, 0) + #{delta} " +
            "WHERE user_id = #{userId}")
    int increaseCreditByUserId(@Param("userId") Long userId, @Param("delta") int delta);

    @Update("UPDATE alphafrog_user " +
            "SET password = #{encodedPassword} " +
            "WHERE user_id = #{userId}")
    int updatePasswordByUserId(@Param("userId") Long userId, @Param("encodedPassword") String encodedPassword);

    @Update("UPDATE alphafrog_user " +
            "SET username = #{username}, email = #{email} " +
            "WHERE user_id = #{userId}")
    int updateProfileByUserId(@Param("userId") Long userId,
                              @Param("username") String username,
                              @Param("email") String email);

    @Delete("DELETE FROM alphafrog_user WHERE username = #{username} AND user_type = #{userType}")
    int deleteUserByUsernameAndType(@Param("username") String username, @Param("userType") int userType);

    @Select("<script>" +
            "SELECT * FROM alphafrog_user " +
            "<where>" +
            "<if test='keyword != null and keyword != \"\"'>" +
            " AND (username ILIKE CONCAT('%', #{keyword}, '%') OR email ILIKE CONCAT('%', #{keyword}, '%'))" +
            "</if>" +
            "<if test='status != null and status != \"\"'>" +
            " AND status = #{status}" +
            "</if>" +
            "</where>" +
            " ORDER BY register_time DESC " +
            " LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    @ResultMap("userResultMap")
    List<User> listUsers(@Param("keyword") String keyword,
                         @Param("status") String status,
                         @Param("limit") int limit,
                         @Param("offset") int offset);

    @Select("<script>" +
            "SELECT COUNT(*) FROM alphafrog_user " +
            "<where>" +
            "<if test='keyword != null and keyword != \"\"'>" +
            " AND (username ILIKE CONCAT('%', #{keyword}, '%') OR email ILIKE CONCAT('%', #{keyword}, '%'))" +
            "</if>" +
            "<if test='status != null and status != \"\"'>" +
            " AND status = #{status}" +
            "</if>" +
            "</where>" +
            "</script>")
    int countUsers(@Param("keyword") String keyword, @Param("status") String status);

    @Update("UPDATE alphafrog_user " +
            "SET status = #{status}, " +
            "    disabled_at = #{disabledAt}, " +
            "    disabled_reason = #{disabledReason}, " +
            "    status_updated_at = CURRENT_TIMESTAMP " +
            "WHERE user_id = #{userId}")
    int updateStatusByUserId(@Param("userId") Long userId,
                             @Param("status") String status,
                             @Param("disabledAt") java.time.OffsetDateTime disabledAt,
                             @Param("disabledReason") String disabledReason);

}

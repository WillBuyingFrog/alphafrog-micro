package world.willfrog.alphafrogmicro.common.dao.user;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import world.willfrog.alphafrogmicro.common.pojo.user.UserInviteCode;

@Mapper
public interface UserInviteCodeDao {

    @Insert("INSERT INTO alphafrog_user_invite_code (" +
            "invite_code, created_by, expires_at, ext" +
            ") VALUES (" +
            "#{inviteCode}, #{createdBy}, #{expiresAt}, CAST(#{ext} AS jsonb)" +
            ")")
    int insert(UserInviteCode inviteCode);

    @Select("SELECT * FROM alphafrog_user_invite_code WHERE invite_code = #{inviteCode} LIMIT 1")
    @Results(id = "inviteCodeResultMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "invite_code", property = "inviteCode"),
            @Result(column = "created_by", property = "createdBy"),
            @Result(column = "used_by", property = "usedBy"),
            @Result(column = "status", property = "status"),
            @Result(column = "ext", property = "ext"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "used_at", property = "usedAt")
    })
    UserInviteCode getByInviteCode(@Param("inviteCode") String inviteCode);

    @Update("UPDATE alphafrog_user_invite_code " +
            "SET used_by = #{usedBy}, used_at = CURRENT_TIMESTAMP, status = 'USED' " +
            "WHERE invite_code = #{inviteCode} " +
            "  AND status = 'ACTIVE' " +
            "  AND used_by IS NULL " +
            "  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)")
    int consumeInviteCode(@Param("inviteCode") String inviteCode, @Param("usedBy") Long usedBy);
}

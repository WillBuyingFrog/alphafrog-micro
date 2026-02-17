package world.willfrog.alphafrogmicro.common.dao.agent;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentCreditApplication;

import java.util.List;

@Mapper
public interface AgentCreditApplicationDao {

    @Insert("INSERT INTO alphafrog_agent_credit_application (" +
            "application_id, user_id, amount, reason, contact, status, processed_by, process_reason, version, ext, processed_at" +
            ") VALUES (" +
            "#{applicationId}, #{userId}, #{amount}, #{reason}, #{contact}, #{status}, #{processedBy}, #{processReason}, #{version}, CAST(#{ext} AS jsonb), #{processedAt}" +
            ")")
    int insert(AgentCreditApplication application);

    @Select("SELECT * FROM alphafrog_agent_credit_application WHERE application_id = #{applicationId}")
    @Results(id = "creditApplicationResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "applicationId", column = "application_id"),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "amount", column = "amount"),
            @Result(property = "reason", column = "reason"),
            @Result(property = "contact", column = "contact"),
            @Result(property = "status", column = "status"),
            @Result(property = "processedBy", column = "processed_by"),
            @Result(property = "processReason", column = "process_reason"),
            @Result(property = "version", column = "version"),
            @Result(property = "ext", column = "ext"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "processedAt", column = "processed_at")
    })
    AgentCreditApplication getByApplicationId(String applicationId);

    @Select("SELECT * FROM alphafrog_agent_credit_application WHERE application_id = #{applicationId}")
    @ResultMap("creditApplicationResultMap")
    AgentCreditApplication getByApplicationIdWithStatusVersion(String applicationId);

    @Select("SELECT * FROM alphafrog_agent_credit_application WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT #{limit}")
    @ResultMap("creditApplicationResultMap")
    List<AgentCreditApplication> listByUserId(@Param("userId") String userId, @Param("limit") int limit);

    @Select("<script>" +
            "SELECT * FROM alphafrog_agent_credit_application " +
            "<where>" +
            "<if test='status != null and status != \"\"'> AND status = #{status}</if>" +
            "<if test='userId != null and userId != \"\"'> AND user_id = #{userId}</if>" +
            "</where>" +
            "ORDER BY created_at DESC LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    @ResultMap("creditApplicationResultMap")
    List<AgentCreditApplication> listByStatus(@Param("status") String status, @Param("userId") String userId, 
                                               @Param("limit") int limit, @Param("offset") int offset);

    @Select("<script>" +
            "SELECT COUNT(*) FROM alphafrog_agent_credit_application " +
            "<where>" +
            "<if test='status != null and status != \"\"'> AND status = #{status}</if>" +
            "<if test='userId != null and userId != \"\"'> AND user_id = #{userId}</if>" +
            "</where>" +
            "</script>")
    int countByStatus(@Param("status") String status, @Param("userId") String userId);

    @Update("UPDATE alphafrog_agent_credit_application SET status = #{status}, processed_at = #{processedAt} " +
            "WHERE application_id = #{applicationId}")
    int updateStatus(@Param("applicationId") String applicationId, @Param("status") String status, 
                     @Param("processedAt") java.time.OffsetDateTime processedAt);

    @Update("UPDATE alphafrog_agent_credit_application " +
            "SET status = #{status}, " +
            "    processed_at = #{processedAt}, " +
            "    processed_by = #{processedBy}, " +
            "    process_reason = #{processReason}, " +
            "    version = version + 1 " +
            "WHERE application_id = #{applicationId} " +
            "  AND status = 'PENDING' " +
            "  AND version = #{expectedVersion}")
    int updateStatusWithVersion(@Param("applicationId") String applicationId,
                                @Param("status") String status,
                                @Param("processedAt") java.time.OffsetDateTime processedAt,
                                @Param("processedBy") String processedBy,
                                @Param("processReason") String processReason,
                                @Param("expectedVersion") int expectedVersion);
}

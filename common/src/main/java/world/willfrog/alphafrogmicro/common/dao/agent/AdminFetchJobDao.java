package world.willfrog.alphafrogmicro.common.dao.agent;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.agent.AdminFetchJob;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface AdminFetchJobDao {

    @Insert("INSERT INTO alphafrog_admin_fetch_job (" +
            "job_uuid, mode, label, status, requested_spec, normalized_spec, " +
            "expanded_task_count, pending_count, running_count, success_count, failure_count, " +
            "created_by, created_at, updated_at, execution_options" +
            ") VALUES (" +
            "#{jobUuid}, #{mode}, #{label}, #{status}, CAST(#{requestedSpec} AS jsonb), CAST(#{normalizedSpec} AS jsonb), " +
            "#{expandedTaskCount}, #{pendingCount}, #{runningCount}, #{successCount}, #{failureCount}, " +
            "#{createdBy}, #{createdAt}, #{updatedAt}, CAST(#{executionOptions} AS jsonb)" +
            ")")
    int insert(AdminFetchJob job);

    @Select("SELECT * FROM alphafrog_admin_fetch_job WHERE job_uuid = #{jobUuid}")
    @Results(id = "adminFetchJobResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "jobUuid", column = "job_uuid"),
            @Result(property = "mode", column = "mode"),
            @Result(property = "label", column = "label"),
            @Result(property = "status", column = "status"),
            @Result(property = "requestedSpec", column = "requested_spec"),
            @Result(property = "normalizedSpec", column = "normalized_spec"),
            @Result(property = "expandedTaskCount", column = "expanded_task_count"),
            @Result(property = "pendingCount", column = "pending_count"),
            @Result(property = "runningCount", column = "running_count"),
            @Result(property = "successCount", column = "success_count"),
            @Result(property = "failureCount", column = "failure_count"),
            @Result(property = "createdBy", column = "created_by"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at"),
            @Result(property = "finishedAt", column = "finished_at"),
            @Result(property = "executionOptions", column = "execution_options")
    })
    AdminFetchJob getByJobUuid(String jobUuid);

    @Update("UPDATE alphafrog_admin_fetch_job SET " +
            "pending_count = #{pendingCount}, running_count = #{runningCount}, " +
            "success_count = #{successCount}, failure_count = #{failureCount}, " +
            "updated_at = #{updatedAt}, finished_at = #{finishedAt}, " +
            "status = CASE WHEN #{pendingCount} = 0 AND #{runningCount} = 0 THEN " +
            "  CASE WHEN #{successCount} > 0 AND #{failureCount} = 0 THEN 'SUCCESS' " +
            "       WHEN #{failureCount} > 0 AND #{successCount} = 0 THEN 'FAILURE' " +
            "       WHEN #{failureCount} > 0 AND #{successCount} > 0 THEN 'PARTIAL_SUCCESS' " +
            "       ELSE status END " +
            "  ELSE status END " +
            "WHERE job_uuid = #{jobUuid}")
    int updateCounters(@Param("jobUuid") String jobUuid,
                       @Param("pendingCount") int pendingCount,
                       @Param("runningCount") int runningCount,
                       @Param("successCount") int successCount,
                       @Param("failureCount") int failureCount,
                       @Param("updatedAt") OffsetDateTime updatedAt,
                       @Param("finishedAt") OffsetDateTime finishedAt);

    @Select("<script>" +
            "SELECT * FROM alphafrog_admin_fetch_job " +
            "<where>" +
            "<if test='status != null and status != \"\"'> AND status = #{status}</if>" +
            "<if test='mode != null and mode != \"\"'> AND mode = #{mode}</if>" +
            "<if test='jobUuid != null and jobUuid != \"\"'> AND job_uuid = #{jobUuid}</if>" +
            "<if test='createdFrom != null and createdFrom != \"\"'> AND created_at &gt;= CAST(#{createdFrom} AS TIMESTAMP WITH TIME ZONE)</if>" +
            "<if test='createdTo != null and createdTo != \"\"'> AND created_at &lt;= CAST(#{createdTo} AS TIMESTAMP WITH TIME ZONE)</if>" +
            "</where>" +
            "ORDER BY created_at DESC LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    @ResultMap("adminFetchJobResultMap")
    List<AdminFetchJob> listByConditions(@Param("status") String status,
                                         @Param("mode") String mode,
                                         @Param("jobUuid") String jobUuid,
                                         @Param("createdFrom") String createdFrom,
                                         @Param("createdTo") String createdTo,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);

    @Select("<script>" +
            "SELECT COUNT(*) FROM alphafrog_admin_fetch_job " +
            "<where>" +
            "<if test='status != null and status != \"\"'> AND status = #{status}</if>" +
            "<if test='mode != null and mode != \"\"'> AND mode = #{mode}</if>" +
            "<if test='jobUuid != null and jobUuid != \"\"'> AND job_uuid = #{jobUuid}</if>" +
            "<if test='createdFrom != null and createdFrom != \"\"'> AND created_at &gt;= CAST(#{createdFrom} AS TIMESTAMP WITH TIME ZONE)</if>" +
            "<if test='createdTo != null and createdTo != \"\"'> AND created_at &lt;= CAST(#{createdTo} AS TIMESTAMP WITH TIME ZONE)</if>" +
            "</where>" +
            "</script>")
    int countByConditions(@Param("status") String status,
                          @Param("mode") String mode,
                          @Param("jobUuid") String jobUuid,
                          @Param("createdFrom") String createdFrom,
                          @Param("createdTo") String createdTo);

    @Select("SELECT COUNT(*) FROM alphafrog_admin_fetch_job WHERE status = 'RUNNING'")
    int countRunning();

    @Select("SELECT COUNT(*) FROM alphafrog_admin_fetch_job WHERE status = #{status} AND created_at >= #{startOfDay} AND created_at < #{endOfDay}")
    int countTodayByStatus(@Param("status") String status,
                           @Param("startOfDay") OffsetDateTime startOfDay,
                           @Param("endOfDay") OffsetDateTime endOfDay);

    @Update("UPDATE alphafrog_admin_fetch_job SET status = #{status}, updated_at = #{updatedAt}, finished_at = #{finishedAt} WHERE job_uuid = #{jobUuid}")
    int updateStatus(@Param("jobUuid") String jobUuid,
                     @Param("status") String status,
                     @Param("updatedAt") OffsetDateTime updatedAt,
                     @Param("finishedAt") OffsetDateTime finishedAt);

    @Delete("DELETE FROM alphafrog_admin_fetch_job WHERE job_uuid = #{jobUuid}")
    int deleteByJobUuid(@Param("jobUuid") String jobUuid);
}

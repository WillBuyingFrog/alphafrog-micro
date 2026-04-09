package world.willfrog.alphafrogmicro.common.dao.agent;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.agent.AdminFetchTask;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface AdminFetchTaskDao {

    @Insert("INSERT INTO alphafrog_admin_fetch_task (" +
            "task_uuid, template_key, task_name, task_sub_type, status, fetched_items_count, message, " +
            "params_summary, input_params, dispatch_payload, created_by, created_at, updated_at, retry_of_task_uuid" +
            ") VALUES (" +
            "#{taskUuid}, #{templateKey}, #{taskName}, #{taskSubType}, #{status}, #{fetchedItemsCount}, #{message}, " +
            "#{paramsSummary}, CAST(#{inputParams} AS jsonb), CAST(#{dispatchPayload} AS jsonb), #{createdBy}, #{createdAt}, #{updatedAt}, #{retryOfTaskUuid}" +
            ")")
    int insert(AdminFetchTask task);

    @Select("SELECT * FROM alphafrog_admin_fetch_task WHERE task_uuid = #{taskUuid}")
    @Results(id = "adminFetchTaskResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "taskUuid", column = "task_uuid"),
            @Result(property = "templateKey", column = "template_key"),
            @Result(property = "taskName", column = "task_name"),
            @Result(property = "taskSubType", column = "task_sub_type"),
            @Result(property = "status", column = "status"),
            @Result(property = "fetchedItemsCount", column = "fetched_items_count"),
            @Result(property = "message", column = "message"),
            @Result(property = "paramsSummary", column = "params_summary"),
            @Result(property = "inputParams", column = "input_params"),
            @Result(property = "dispatchPayload", column = "dispatch_payload"),
            @Result(property = "createdBy", column = "created_by"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at"),
            @Result(property = "finishedAt", column = "finished_at"),
            @Result(property = "retryOfTaskUuid", column = "retry_of_task_uuid")
    })
    AdminFetchTask getByTaskUuid(String taskUuid);

    @Update("UPDATE alphafrog_admin_fetch_task SET status = #{status}, updated_at = #{updatedAt} WHERE task_uuid = #{taskUuid}")
    int updateStatus(@Param("taskUuid") String taskUuid,
                     @Param("status") String status,
                     @Param("updatedAt") OffsetDateTime updatedAt);

    @Update("UPDATE alphafrog_admin_fetch_task SET status = 'RUNNING', updated_at = #{updatedAt} WHERE task_uuid = #{taskUuid}")
    int markRunning(@Param("taskUuid") String taskUuid,
                    @Param("updatedAt") OffsetDateTime updatedAt);

    @Update("UPDATE alphafrog_admin_fetch_task SET status = 'SUCCESS', fetched_items_count = #{fetchedItemsCount}, " +
            "updated_at = #{updatedAt}, finished_at = #{finishedAt} WHERE task_uuid = #{taskUuid}")
    int markSuccess(@Param("taskUuid") String taskUuid,
                    @Param("fetchedItemsCount") int fetchedItemsCount,
                    @Param("updatedAt") OffsetDateTime updatedAt,
                    @Param("finishedAt") OffsetDateTime finishedAt);

    @Update("UPDATE alphafrog_admin_fetch_task SET status = 'FAILURE', fetched_items_count = #{fetchedItemsCount}, " +
            "message = #{message}, updated_at = #{updatedAt}, finished_at = #{finishedAt} WHERE task_uuid = #{taskUuid}")
    int markFailure(@Param("taskUuid") String taskUuid,
                    @Param("fetchedItemsCount") int fetchedItemsCount,
                    @Param("message") String message,
                    @Param("updatedAt") OffsetDateTime updatedAt,
                    @Param("finishedAt") OffsetDateTime finishedAt);

    @Select("<script>" +
            "SELECT * FROM alphafrog_admin_fetch_task " +
            "<where>" +
            "<if test='status != null and status != \"\"'> AND status = #{status}</if>" +
            "<if test='templateKey != null and templateKey != \"\"'> AND template_key = #{templateKey}</if>" +
            "<if test='taskUuid != null and taskUuid != \"\"'> AND task_uuid = #{taskUuid}</if>" +
            "<if test='createdFrom != null and createdFrom != \"\"'> AND created_at &gt;= CAST(#{createdFrom} AS TIMESTAMP WITH TIME ZONE)</if>" +
            "<if test='createdTo != null and createdTo != \"\"'> AND created_at &lt;= CAST(#{createdTo} AS TIMESTAMP WITH TIME ZONE)</if>" +
            "</where>" +
            "ORDER BY updated_at DESC LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    @ResultMap("adminFetchTaskResultMap")
    List<AdminFetchTask> listByConditions(@Param("status") String status,
                                          @Param("templateKey") String templateKey,
                                          @Param("taskUuid") String taskUuid,
                                          @Param("createdFrom") String createdFrom,
                                          @Param("createdTo") String createdTo,
                                          @Param("limit") int limit,
                                          @Param("offset") int offset);

    @Select("<script>" +
            "SELECT COUNT(*) FROM alphafrog_admin_fetch_task " +
            "<where>" +
            "<if test='status != null and status != \"\"'> AND status = #{status}</if>" +
            "<if test='templateKey != null and templateKey != \"\"'> AND template_key = #{templateKey}</if>" +
            "<if test='taskUuid != null and taskUuid != \"\"'> AND task_uuid = #{taskUuid}</if>" +
            "<if test='createdFrom != null and createdFrom != \"\"'> AND created_at &gt;= CAST(#{createdFrom} AS TIMESTAMP WITH TIME ZONE)</if>" +
            "<if test='createdTo != null and createdTo != \"\"'> AND created_at &lt;= CAST(#{createdTo} AS TIMESTAMP WITH TIME ZONE)</if>" +
            "</where>" +
            "</script>")
    int countByConditions(@Param("status") String status,
                          @Param("templateKey") String templateKey,
                          @Param("taskUuid") String taskUuid,
                          @Param("createdFrom") String createdFrom,
                          @Param("createdTo") String createdTo);

    @Select("SELECT COUNT(*) FROM alphafrog_admin_fetch_task WHERE status = 'RUNNING'")
    int countRunning();

    @Select("SELECT COUNT(*) FROM alphafrog_admin_fetch_task WHERE status = #{status} AND created_at >= #{startOfDay} AND created_at < #{endOfDay}")
    int countTodayByStatus(@Param("status") String status,
                           @Param("startOfDay") OffsetDateTime startOfDay,
                           @Param("endOfDay") OffsetDateTime endOfDay);
}

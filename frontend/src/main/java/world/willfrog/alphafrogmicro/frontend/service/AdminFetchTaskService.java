package world.willfrog.alphafrogmicro.frontend.service;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.alphafrogmicro.common.dao.agent.AdminFetchTaskDao;
import world.willfrog.alphafrogmicro.common.pojo.agent.AdminFetchTask;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradeCalendarFetchByDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradeCalendarFetchService;
import world.willfrog.alphafrogmicro.frontend.config.TaskProducerRabbitConfig;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * admin 抓取任务服务（叶子任务级别）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminFetchTaskService {

    private final AdminFetchTaskDao adminFetchTaskDao;
    private final RabbitTemplate rabbitTemplate;
    private final FetchQueueService fetchQueueService;

    @DubboReference
    private DomesticTradeCalendarFetchService domesticTradeCalendarFetchService;

    private static final String DEFAULT_DIRECT_EXCHANGE = "";
    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");

    // 允许的模板枚举
    private static final Set<String> VALID_TEMPLATES = Set.of(
            "stock_quote_range",
            "index_quote_trade_date",
            "index_quote_range",
            "index_weight_range",
            "fund_portfolio_range",
            "trade_calendar_range"
    );

    /**
     * 创建任务（v1 兼容）
     */
    @Transactional
    public AdminFetchTask createTask(String templateKey, Map<String, Object> inputParams, String createdBy) {
        if (!VALID_TEMPLATES.contains(templateKey)) {
            throw new IllegalArgumentException("Invalid templateKey: " + templateKey);
        }
        if (inputParams == null) {
            inputParams = new HashMap<>();
        }

        String taskUuid = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        OffsetDateTime nowDateTime = OffsetDateTime.now();

        // 构建派发信息
        DispatchInfo dispatchInfo = buildDispatchInfo(templateKey, inputParams);

        // 生成参数摘要
        String paramsSummary = buildParamsSummary(templateKey, inputParams);

        // 持久化记录
        AdminFetchTask task = new AdminFetchTask();
        task.setTaskUuid(taskUuid);
        task.setTemplateKey(templateKey);
        task.setTaskName(dispatchInfo.taskName);
        task.setTaskSubType(dispatchInfo.taskSubType);
        task.setStatus("PENDING");
        task.setFetchedItemsCount(0);
        task.setParamsSummary(paramsSummary);
        task.setInputParams(JSONObject.toJSONString(inputParams));
        task.setDispatchPayload(dispatchInfo.dispatchPayload);
        task.setCreatedBy(createdBy);
        task.setCreatedAt(nowDateTime);
        task.setUpdatedAt(nowDateTime);

        adminFetchTaskDao.insert(task);

        // 派发任务
        dispatchTask(taskUuid, dispatchInfo, task);

        return task;
    }

    /**
     * 分页查询 + 概览
     */
    public Map<String, Object> listTasks(String status, String templateKey, String taskUuid,
                                         String jobUuid, String taskName, Integer taskSubType, String sourceKind,
                                         String createdFrom, String createdTo, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<AdminFetchTask> items = adminFetchTaskDao.listByConditions(
                status, templateKey, taskUuid, jobUuid, taskName, taskSubType, sourceKind, createdFrom, createdTo, pageSize, offset);
        int total = adminFetchTaskDao.countByConditions(
                status, templateKey, taskUuid, jobUuid, taskName, taskSubType, sourceKind, createdFrom, createdTo);

        FetchQueueService.FetchQueueStats stats = fetchQueueService.getFetchQueueStats();
        int runningCount = adminFetchTaskDao.countRunning();

        OffsetDateTime todayStart = LocalDate.now(ZONE_SHANGHAI).atStartOfDay(ZONE_SHANGHAI).toOffsetDateTime();
        OffsetDateTime todayEnd = todayStart.plusDays(1);
        int successToday = adminFetchTaskDao.countTodayByStatus("SUCCESS", todayStart, todayEnd);
        int failureToday = adminFetchTaskDao.countTodayByStatus("FAILURE", todayStart, todayEnd);

        Map<String, Object> summary = new HashMap<>();
        summary.put("queuePending", stats.pending());
        summary.put("queueConsumers", stats.consumers());
        summary.put("runningCount", runningCount);
        summary.put("successToday", successToday);
        summary.put("failureToday", failureToday);

        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("summary", summary);
        return result;
    }

    /**
     * 详情
     */
    public AdminFetchTask getTaskDetail(String taskUuid) {
        return adminFetchTaskDao.getByTaskUuid(taskUuid);
    }

    /**
     * 批量重试
     */
    @Transactional
    public List<Map<String, Object>> retryTasks(List<String> taskUuids) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String sourceTaskUuid : taskUuids) {
            Map<String, Object> result = new HashMap<>();
            result.put("sourceTaskUuid", sourceTaskUuid);
            result.put("newTaskUuid", "");
            result.put("success", false);

            AdminFetchTask sourceTask = adminFetchTaskDao.getByTaskUuid(sourceTaskUuid);
            if (sourceTask == null) {
                result.put("message", "source task not found");
                results.add(result);
                continue;
            }
            if (sourceTask.getDispatchPayload() == null || sourceTask.getDispatchPayload().isBlank()) {
                result.put("message", "task payload missing");
                results.add(result);
                continue;
            }

            String newTaskUuid = UUID.randomUUID().toString();
            OffsetDateTime nowDateTime = OffsetDateTime.now();

            AdminFetchTask newTask = new AdminFetchTask();
            newTask.setTaskUuid(newTaskUuid);
            newTask.setTemplateKey(sourceTask.getTemplateKey());
            newTask.setTaskName(sourceTask.getTaskName());
            newTask.setTaskSubType(sourceTask.getTaskSubType());
            newTask.setStatus("PENDING");
            newTask.setFetchedItemsCount(0);
            newTask.setParamsSummary(sourceTask.getParamsSummary());
            newTask.setInputParams(sourceTask.getInputParams());
            newTask.setDispatchPayload(sourceTask.getDispatchPayload());
            newTask.setCreatedBy(sourceTask.getCreatedBy());
            newTask.setCreatedAt(nowDateTime);
            newTask.setUpdatedAt(nowDateTime);
            newTask.setRetryOfTaskUuid(sourceTaskUuid);
            // v2 字段继承
            newTask.setJobUuid(sourceTask.getJobUuid());
            newTask.setSourceKind(sourceTask.getSourceKind());
            newTask.setSourceIndex(sourceTask.getSourceIndex());
            newTask.setTaskSetMode(sourceTask.getTaskSetMode());

            adminFetchTaskDao.insert(newTask);

            // 解析 dispatchPayload 重新派发
            JSONObject dispatchObj = JSONObject.parseObject(sourceTask.getDispatchPayload());
            DispatchInfo dispatchInfo = new DispatchInfo();
            dispatchInfo.taskName = sourceTask.getTaskName();
            dispatchInfo.taskSubType = sourceTask.getTaskSubType();
            dispatchInfo.dispatchPayload = sourceTask.getDispatchPayload();
            dispatchInfo.isTradeCalendar = "trade_calendar".equals(sourceTask.getTaskName());

            try {
                dispatchTask(newTaskUuid, dispatchInfo, newTask);
                result.put("newTaskUuid", newTaskUuid);
                result.put("success", true);
                result.put("message", "retry created");
            } catch (Exception e) {
                log.error("Failed to dispatch retry task source={}", sourceTaskUuid, e);
                result.put("message", e.getMessage());
            }
            results.add(result);
        }
        return results;
    }

    /**
     * 更新任务状态为 RUNNING（供 Controller 或 Listener 使用）
     */
    public void markRunning(String taskUuid) {
        adminFetchTaskDao.markRunning(taskUuid, OffsetDateTime.now());
    }

    /**
     * 更新任务状态为 SUCCESS（供 Listener 使用）
     */
    public void markSuccess(String taskUuid, int fetchedItemsCount) {
        OffsetDateTime now = OffsetDateTime.now();
        adminFetchTaskDao.markSuccess(taskUuid, fetchedItemsCount, now, now);
    }

    /**
     * 更新任务状态为 FAILURE（供 Listener 使用）
     */
    public void markFailure(String taskUuid, int fetchedItemsCount, String message) {
        OffsetDateTime now = OffsetDateTime.now();
        adminFetchTaskDao.markFailure(taskUuid, fetchedItemsCount, message, now, now);
    }

    // ==================== 私有方法 ====================

    private DispatchInfo buildDispatchInfo(String templateKey, Map<String, Object> inputParams) {
        DispatchInfo info = new DispatchInfo();
        JSONObject params = new JSONObject();
        int offset = getIntParam(inputParams, "offset", 0);
        int limit = getIntParam(inputParams, "limit", 10);

        switch (templateKey) {
            case "stock_quote_range" -> {
                info.taskName = "stock_quote";
                info.taskSubType = 1;
                long startTs = parseDateToTimestamp(getStringParam(inputParams, "startDate"));
                long endTs = parseDateToTimestamp(getStringParam(inputParams, "endDate"));
                params.put("start_date_timestamp", startTs);
                params.put("end_date_timestamp", endTs);
            }
            case "index_quote_trade_date" -> {
                info.taskName = "index_quote";
                info.taskSubType = 1;
                String startDate = getStringParam(inputParams, "startDate");
                String endDate = getStringParam(inputParams, "endDate");
                long tradeDateTs = parseDateToTimestamp(startDate != null ? startDate : endDate);
                params.put("trade_date_timestamp", tradeDateTs);
            }
            case "index_quote_range" -> {
                info.taskName = "index_quote";
                info.taskSubType = 2;
                long startTs = parseDateToTimestamp(getStringParam(inputParams, "startDate"));
                long endTs = parseDateToTimestamp(getStringParam(inputParams, "endDate"));
                params.put("start_date_timestamp", startTs);
                params.put("end_date_timestamp", endTs);
            }
            case "index_weight_range" -> {
                info.taskName = "index_weight";
                info.taskSubType = 1;
                long startTs = parseDateToTimestamp(getStringParam(inputParams, "startDate"));
                long endTs = parseDateToTimestamp(getStringParam(inputParams, "endDate"));
                params.put("start_date_timestamp", startTs);
                params.put("end_date_timestamp", endTs);
            }
            case "fund_portfolio_range" -> {
                info.taskName = "fund_portfolio";
                info.taskSubType = 1;
                long startTs = parseDateToTimestamp(getStringParam(inputParams, "startDate"));
                long endTs = parseDateToTimestamp(getStringParam(inputParams, "endDate"));
                params.put("start_date_timestamp", startTs);
                params.put("end_date_timestamp", endTs);
            }
            case "trade_calendar_range" -> {
                info.taskName = "trade_calendar";
                info.taskSubType = 1;
                info.isTradeCalendar = true;
                long startTs = parseDateToTimestamp(getStringParam(inputParams, "startDate"));
                long endTs = parseDateToTimestamp(getStringParam(inputParams, "endDate"));
                params.put("start_date_timestamp", startTs);
                params.put("end_date_timestamp", endTs);
            }
            default -> throw new IllegalArgumentException("Unsupported templateKey: " + templateKey);
        }

        params.put("offset", offset);
        params.put("limit", limit);

        JSONObject payload = new JSONObject();
        payload.put("task_type", "fetch");
        payload.put("task_name", info.taskName);
        payload.put("task_sub_type", info.taskSubType);
        payload.put("task_params", params);

        info.dispatchPayload = payload.toJSONString();
        return info;
    }

    private void dispatchTask(String taskUuid, DispatchInfo dispatchInfo, AdminFetchTask taskRecord) {
        if (dispatchInfo.isTradeCalendar) {
            // Dubbo 异步调用
            JSONObject params = JSONObject.parseObject(dispatchInfo.dispatchPayload)
                    .getJSONObject("task_params");
            long startDateTimestamp = params.getLongValue("start_date_timestamp");
            long endDateTimestamp = params.getLongValue("end_date_timestamp");
            int offset = params.getIntValue("offset");
            int limit = params.getIntValue("limit");

            DomesticTradeCalendarFetchByDateRangeRequest request =
                    DomesticTradeCalendarFetchByDateRangeRequest.newBuilder()
                            .setStartDate(startDateTimestamp)
                            .setEndDate(endDateTimestamp)
                            .setOffset(offset)
                            .setLimit(limit)
                            .build();

            var future = domesticTradeCalendarFetchService.fetchDomesticTradeCalendarByDateRangeAsync(request);

            // 标记为 RUNNING
            adminFetchTaskDao.markRunning(taskUuid, OffsetDateTime.now());

            future.whenComplete((response, ex) -> {
                if (ex != null) {
                    log.error("Admin fetch task failed taskUuid={} taskName=trade_calendar", taskUuid, ex);
                    markFailure(taskUuid, -1, ex.getMessage());
                    return;
                }
                int fetchedItemsCount = response.getFetchedItemsCount();
                if ("success".equalsIgnoreCase(response.getStatus())) {
                    markSuccess(taskUuid, fetchedItemsCount);
                } else {
                    markFailure(taskUuid, fetchedItemsCount, response.getStatus());
                }
            });
        } else {
            // RabbitMQ 发送
            JSONObject payload = JSONObject.parseObject(dispatchInfo.dispatchPayload);
            payload.put("task_uuid", taskUuid);

            try {
                rabbitTemplate.convertAndSend(
                        TaskProducerRabbitConfig.FETCH_EXCHANGE,
                        TaskProducerRabbitConfig.FETCH_TASK_ROUTING_KEY,
                        payload.toJSONString()
                );
                adminFetchTaskDao.markRunning(taskUuid, OffsetDateTime.now());
            } catch (Exception e) {
                log.error("Failed to send admin fetch task to rabbitmq taskUuid={}", taskUuid, e);
                OffsetDateTime now = OffsetDateTime.now();
                adminFetchTaskDao.markFailure(taskUuid, -1, e.getMessage(), now, now);
                throw new RuntimeException("Failed to dispatch task", e);
            }
        }
    }

    private String buildParamsSummary(String templateKey, Map<String, Object> inputParams) {
        StringBuilder sb = new StringBuilder();
        String startDate = getStringParam(inputParams, "startDate");
        String endDate = getStringParam(inputParams, "endDate");
        int offset = getIntParam(inputParams, "offset", 0);
        int limit = getIntParam(inputParams, "limit", 10);

        if (startDate != null && endDate != null) {
            if (startDate.equals(endDate)) {
                sb.append(startDate);
            } else {
                sb.append(startDate).append(" ~ ").append(endDate);
            }
        } else if (startDate != null) {
            sb.append(startDate);
        } else if (endDate != null) {
            sb.append(endDate);
        }

        if (!sb.isEmpty()) {
            sb.append(", ");
        }
        sb.append("offset=").append(offset).append(", limit=").append(limit);
        return sb.toString();
    }

    private long parseDateToTimestamp(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            throw new IllegalArgumentException("Date parameter is required");
        }
        LocalDate date = LocalDate.parse(dateStr);
        return date.atStartOfDay(ZONE_SHANGHAI).toInstant().toEpochMilli();
    }

    private String getStringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : value.toString();
    }

    private int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static class DispatchInfo {
        String taskName;
        int taskSubType;
        String dispatchPayload;
        boolean isTradeCalendar;
    }
}

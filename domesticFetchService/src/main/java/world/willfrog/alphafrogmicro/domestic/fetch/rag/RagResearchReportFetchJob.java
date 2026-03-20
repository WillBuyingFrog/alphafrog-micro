package world.willfrog.alphafrogmicro.domestic.fetch.rag;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.domestic.fetch.config.FetchJobsConfig;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.TuShareRequestUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时拉取券商研究报告元数据（TuShare research_report 接口）并存入 DB。
 * 从 externalInfoService 迁移，使用 domesticFetchService 统一的 TuShareRequestUtils。
 */
@Component
@Slf4j
public class RagResearchReportFetchJob {

    private final TuShareRequestUtils tuShareRequestUtils;
    private final RagResearchReportDao researchReportDao;
    private final FetchJobsConfig fetchJobsConfig;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String API_NAME = "research_report";
    private static final String FIELDS = "trade_date,title,abstr,report_type,author,name,ts_code,inst_csname,ind_name,url";
    public static final int DEFAULT_PAGE_LIMIT = 1000;

    public RagResearchReportFetchJob(TuShareRequestUtils tuShareRequestUtils,
                                     RagResearchReportDao researchReportDao,
                                     FetchJobsConfig fetchJobsConfig) {
        this.tuShareRequestUtils = tuShareRequestUtils;
        this.researchReportDao = researchReportDao;
        this.fetchJobsConfig = fetchJobsConfig;
    }

    /**
     * 每天早 6:30 增量更新（拉昨日研报，全量行业，自动分页）。
     */
    @Scheduled(cron = "0 30 6 * * *")
    public void incrementalFetch() {
        // 检查配置是否启用
        if (!fetchJobsConfig.isJobEnabled("ragResearchReportFetch")) {
            log.info("[RagResearchReportFetchJob] 定时任务已禁用，跳过执行");
            return;
        }
        
        String yesterday = LocalDate.now().minusDays(1).format(DATE_FMT);
        log.info("[RagResearchReportFetchJob] 增量抓取 date={}", yesterday);
        fetchRange(yesterday, yesterday, (String) null);
    }

    /**
     * 按日期范围和单个行业自动分页拉取研报，按月分段调用。
     * offset 从 0 开始自动递增，供定时任务和旧调用路径使用。
     *
     * @param indName 行业名称，null 表示全量
     * @return 总插入条数
     */
    public int fetchRange(String startDate, String endDate, String indName) {
        LocalDate start = LocalDate.parse(startDate, DATE_FMT);
        LocalDate end = LocalDate.parse(endDate, DATE_FMT);
        int total = 0;
        LocalDate segStart = start;
        while (!segStart.isAfter(end)) {
            LocalDate segEnd = segStart.withDayOfMonth(segStart.lengthOfMonth());
            if (segEnd.isAfter(end)) segEnd = end;
            try {
                total += fetchSegmentFull(segStart.format(DATE_FMT), segEnd.format(DATE_FMT), indName);
            } catch (Exception e) {
                log.error("[RagResearchReportFetchJob] 分段抓取失败 {}-{} ind={}: {}",
                        segStart.format(DATE_FMT), segEnd.format(DATE_FMT), indName, e.getMessage(), e);
            }
            segStart = segEnd.plusDays(1);
        }
        return total;
    }

    /**
     * 按日期范围和行业列表自动分页拉取研报。
     * 若列表为空则全量不过滤；否则对每个行业分别拉取。
     * 供定时任务和旧调用路径（RagFetchServiceImpl）使用。
     *
     * @return 总插入条数
     */
    public int fetchRange(String startDate, String endDate, List<String> industries) {
        if (industries == null || industries.isEmpty()) {
            return fetchRange(startDate, endDate, (String) null);
        }
        int total = 0;
        for (String indName : industries) {
            total += fetchRange(startDate, endDate, indName);
        }
        return total;
    }

    /**
     * 按日期范围和行业列表拉取研报，每个月分段只发起一次 API 请求，
     * offset 和 limit 对所有分段/行业统一生效。
     * 如需跨多个分页拉取，请在 YAML 中通过 task_sets 生成多个不同 offset 的任务。
     *
     * @param offset 传给 Tushare 的起始偏移量（每个分段相同）
     * @param limit  传给 Tushare 的单次返回条数上限（每个分段相同）
     * @return 总插入条数
     */
    public int fetchRange(String startDate, String endDate, List<String> industries, int offset, int limit) {
        if (industries == null || industries.isEmpty()) {
            return fetchRange(startDate, endDate, (String) null, offset, limit);
        }
        int total = 0;
        for (String indName : industries) {
            total += fetchRange(startDate, endDate, indName, offset, limit);
        }
        return total;
    }

    /**
     * 按日期范围和单个行业拉取研报，每个月分段只发起一次 API 请求。
     *
     * @param offset 传给 Tushare 的起始偏移量（每个分段相同）
     * @param limit  传给 Tushare 的单次返回条数上限（每个分段相同）
     * @return 总插入条数
     */
    public int fetchRange(String startDate, String endDate, String indName, int offset, int limit) {
        LocalDate start = LocalDate.parse(startDate, DATE_FMT);
        LocalDate end = LocalDate.parse(endDate, DATE_FMT);
        int total = 0;
        LocalDate segStart = start;
        while (!segStart.isAfter(end)) {
            LocalDate segEnd = segStart.withDayOfMonth(segStart.lengthOfMonth());
            if (segEnd.isAfter(end)) segEnd = end;
            try {
                total += fetchSegmentOnce(segStart.format(DATE_FMT), segEnd.format(DATE_FMT), indName, offset, limit);
            } catch (Exception e) {
                log.error("[RagResearchReportFetchJob] 分段抓取失败 {}-{} ind={}: {}",
                        segStart.format(DATE_FMT), segEnd.format(DATE_FMT), indName, e.getMessage(), e);
            }
            segStart = segEnd.plusDays(1);
        }
        return total;
    }

    /**
     * 自动分页拉取一个月分段内的所有研报（offset 从 0 自动递增）。
     */
    private int fetchSegmentFull(String startDate, String endDate, String indName) {
        int offset = 0;
        int totalInserted = 0;

        while (true) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("api_name", API_NAME);
            Map<String, Object> apiParams = new LinkedHashMap<>();
            apiParams.put("start_date", startDate);
            apiParams.put("end_date", endDate);
            if (indName != null && !indName.isBlank()) {
                apiParams.put("ind_name", indName);
            }
            apiParams.put("offset", offset);
            apiParams.put("limit", DEFAULT_PAGE_LIMIT);
            params.put("params", apiParams);
            params.put("fields", FIELDS);

            JSONObject resp = tuShareRequestUtils.createTusharePostRequest(params);
            if (resp == null) {
                log.warn("[RagResearchReportFetchJob] 响应为空 {}-{} ind={} offset={}",
                        startDate, endDate, indName, offset);
                break;
            }
            Integer code = resp.getInteger("code");
            if (code == null || code != 0) {
                log.warn("[RagResearchReportFetchJob] API 错误 {}-{} ind={}: code={}, msg={}",
                        startDate, endDate, indName, code, resp.getString("msg"));
                break;
            }
            JSONObject data = resp.getJSONObject("data");
            if (data == null) break;
            JSONArray items = data.getJSONArray("items");
            if (items == null || items.isEmpty()) break;

            int inserted = researchReportDao.batchUpsert(buildRecords(items));
            totalInserted += inserted;
            log.info("[RagResearchReportFetchJob] {}-{} ind={} offset={} fetched={} inserted={}",
                    startDate, endDate, indName, offset, items.size(), inserted);

            if (items.size() < DEFAULT_PAGE_LIMIT) break;
            offset += items.size();
        }

        log.info("[RagResearchReportFetchJob] {}-{} ind={} totalInserted={}",
                startDate, endDate, indName, totalInserted);
        return totalInserted;
    }

    /**
     * 单次 API 调用，使用固定的 offset 和 limit，不自动翻页。
     */
    private int fetchSegmentOnce(String startDate, String endDate, String indName, int offset, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("api_name", API_NAME);
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("start_date", startDate);
        apiParams.put("end_date", endDate);
        if (indName != null && !indName.isBlank()) {
            apiParams.put("ind_name", indName);
        }
        apiParams.put("offset", offset);
        apiParams.put("limit", limit);
        params.put("params", apiParams);
        params.put("fields", FIELDS);

        JSONObject resp = tuShareRequestUtils.createTusharePostRequest(params);
        if (resp == null) {
            log.warn("[RagResearchReportFetchJob] 响应为空 {}-{} ind={} offset={}",
                    startDate, endDate, indName, offset);
            return 0;
        }
        Integer code = resp.getInteger("code");
        if (code == null || code != 0) {
            log.warn("[RagResearchReportFetchJob] API 错误 {}-{} ind={}: code={}, msg={}",
                    startDate, endDate, indName, code, resp.getString("msg"));
            return 0;
        }
        JSONObject data = resp.getJSONObject("data");
        if (data == null) return 0;
        JSONArray items = data.getJSONArray("items");
        if (items == null || items.isEmpty()) return 0;

        int inserted = researchReportDao.batchUpsert(buildRecords(items));
        log.info("[RagResearchReportFetchJob] {}-{} ind={} offset={} limit={} fetched={} inserted={}",
                startDate, endDate, indName, offset, limit, items.size(), inserted);
        return inserted;
    }

    private List<List<String>> buildRecords(JSONArray items) {
        List<List<String>> records = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            JSONArray row = items.getJSONArray(i);
            if (row == null) continue;
            List<String> record = new ArrayList<>();
            for (int j = 0; j < row.size(); j++) {
                Object val = row.get(j);
                record.add(val == null ? null : val.toString());
            }
            records.add(record);
        }
        return records;
    }
}

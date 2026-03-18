package world.willfrog.alphafrogmicro.domestic.fetch.rag;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.domestic.fetch.utils.TuShareRequestUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时拉取上市公司公告元数据（TuShare anns_d 接口）并存入 DB。
 * 从 externalInfoService 迁移，使用 domesticFetchService 统一的 TuShareRequestUtils。
 */
@Component
@Slf4j
public class RagAnnouncementFetchJob {

    private final TuShareRequestUtils tuShareRequestUtils;
    private final RagAnnouncementDao announcementDao;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String API_NAME = "anns_d";
    private static final String FIELDS = "ann_date,ts_code,name,title,url,rec_time";
    public static final int DEFAULT_PAGE_LIMIT = 2000;

    public RagAnnouncementFetchJob(TuShareRequestUtils tuShareRequestUtils,
                                   RagAnnouncementDao announcementDao) {
        this.tuShareRequestUtils = tuShareRequestUtils;
        this.announcementDao = announcementDao;
    }

    /**
     * 每天早 6 点增量更新（拉昨日公告，自动分页拉全量）。
     */
    @Scheduled(cron = "0 0 6 * * *")
    public void incrementalFetch() {
        String yesterday = LocalDate.now().minusDays(1).format(DATE_FMT);
        log.info("[RagAnnouncementFetchJob] 增量抓取 date={}", yesterday);
        fetchRange(yesterday, yesterday);
    }

    /**
     * 按日期范围自动分页拉取所有公告。
     *
     * @return 总插入条数
     */
    public int fetchRange(String startDate, String endDate) {
        return fetchRange(startDate, endDate, (String) null);
    }

    /**
     * 按日期范围自动分页拉取公告，支持 Tushare title LIKE 过滤。
     * offset 从 0 开始，自动递增直至当天数据拉完。
     * 供定时任务和旧调用路径使用。
     *
     * @return 总插入条数
     */
    public int fetchRange(String startDate, String endDate, String titleFilter) {
        LocalDate start = LocalDate.parse(startDate, DATE_FMT);
        LocalDate end = LocalDate.parse(endDate, DATE_FMT);
        int total = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String dateStr = date.format(DATE_FMT);
            try {
                total += fetchSingleDayFull(dateStr, titleFilter);
            } catch (Exception e) {
                log.error("[RagAnnouncementFetchJob] 抓取 date={} 失败: {}", dateStr, e.getMessage(), e);
            }
        }
        return total;
    }

    /**
     * 按日期范围拉取公告，每天只发起一次 API 请求，offset 和 limit 对所有日期统一生效。
     * 如需跨多个分页拉取，请在 YAML 中通过 task_sets 生成多个不同 offset 的任务。
     *
     * @param titleFilter 标题 LIKE 过滤条件，null 或空则不过滤
     * @param offset      传给 Tushare 的起始偏移量（每天相同）
     * @param limit       传给 Tushare 的单次返回条数上限（每天相同）
     * @return 总插入条数
     */
    public int fetchRange(String startDate, String endDate, String titleFilter, int offset, int limit) {
        LocalDate start = LocalDate.parse(startDate, DATE_FMT);
        LocalDate end = LocalDate.parse(endDate, DATE_FMT);
        int total = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String dateStr = date.format(DATE_FMT);
            try {
                total += fetchSingleDayOnce(dateStr, titleFilter, offset, limit);
            } catch (Exception e) {
                log.error("[RagAnnouncementFetchJob] 抓取 date={} 失败: {}", dateStr, e.getMessage(), e);
            }
        }
        return total;
    }

    /**
     * 自动分页拉取一天所有公告（offset 从 0 自动递增至数据拉完）。
     */
    private int fetchSingleDayFull(String dateStr, String titleFilter) {
        int offset = 0;
        int totalInserted = 0;

        while (true) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("api_name", API_NAME);
            Map<String, Object> apiParams = new LinkedHashMap<>();
            apiParams.put("start_date", dateStr);
            apiParams.put("end_date", dateStr);
            if (titleFilter != null && !titleFilter.isBlank()) {
                apiParams.put("title", titleFilter);
            }
            apiParams.put("offset", offset);
            apiParams.put("limit", DEFAULT_PAGE_LIMIT);
            params.put("params", apiParams);
            params.put("fields", FIELDS);

            JSONObject resp = tuShareRequestUtils.createTusharePostRequest(params);
            if (resp == null) {
                log.warn("[RagAnnouncementFetchJob] 响应为空 date={} offset={}", dateStr, offset);
                break;
            }
            Integer code = resp.getInteger("code");
            if (code == null || code != 0) {
                log.warn("[RagAnnouncementFetchJob] API 错误 date={}: code={}, msg={}",
                        dateStr, code, resp.getString("msg"));
                break;
            }
            JSONObject data = resp.getJSONObject("data");
            if (data == null) break;
            JSONArray items = data.getJSONArray("items");
            if (items == null || items.isEmpty()) break;

            int inserted = announcementDao.batchUpsert(buildRecords(items));
            totalInserted += inserted;
            log.info("[RagAnnouncementFetchJob] date={} offset={} fetched={} inserted={}",
                    dateStr, offset, items.size(), inserted);

            if (items.size() < DEFAULT_PAGE_LIMIT) break;
            offset += items.size();
        }

        log.info("[RagAnnouncementFetchJob] date={} titleFilter={} totalInserted={}",
                dateStr, titleFilter, totalInserted);
        return totalInserted;
    }

    /**
     * 单次 API 调用，使用固定的 offset 和 limit，不自动翻页。
     */
    private int fetchSingleDayOnce(String dateStr, String titleFilter, int offset, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("api_name", API_NAME);
        Map<String, Object> apiParams = new LinkedHashMap<>();
        apiParams.put("start_date", dateStr);
        apiParams.put("end_date", dateStr);
        if (titleFilter != null && !titleFilter.isBlank()) {
            apiParams.put("title", titleFilter);
        }
        apiParams.put("offset", offset);
        apiParams.put("limit", limit);
        params.put("params", apiParams);
        params.put("fields", FIELDS);

        JSONObject resp = tuShareRequestUtils.createTusharePostRequest(params);
        if (resp == null) {
            log.warn("[RagAnnouncementFetchJob] 响应为空 date={} offset={}", dateStr, offset);
            return 0;
        }
        Integer code = resp.getInteger("code");
        if (code == null || code != 0) {
            log.warn("[RagAnnouncementFetchJob] API 错误 date={}: code={}, msg={}",
                    dateStr, code, resp.getString("msg"));
            return 0;
        }
        JSONObject data = resp.getJSONObject("data");
        if (data == null) return 0;
        JSONArray items = data.getJSONArray("items");
        if (items == null || items.isEmpty()) return 0;

        int inserted = announcementDao.batchUpsert(buildRecords(items));
        log.info("[RagAnnouncementFetchJob] date={} offset={} limit={} fetched={} inserted={}",
                dateStr, offset, limit, items.size(), inserted);
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

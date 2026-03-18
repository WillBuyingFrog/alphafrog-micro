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
    private static final int PAGE_LIMIT = 2000;

    public RagAnnouncementFetchJob(TuShareRequestUtils tuShareRequestUtils,
                                   RagAnnouncementDao announcementDao) {
        this.tuShareRequestUtils = tuShareRequestUtils;
        this.announcementDao = announcementDao;
    }

    /**
     * 每天早 6 点增量更新（拉昨日公告）。
     */
    @Scheduled(cron = "0 0 6 * * *")
    public void incrementalFetch() {
        String yesterday = LocalDate.now().minusDays(1).format(DATE_FMT);
        log.info("[RagAnnouncementFetchJob] 增量抓取 date={}", yesterday);
        fetchRange(yesterday, yesterday);
    }

    /**
     * 按日期范围拉取公告（供初始化历史数据或手动触发使用）。
     * 每天单独调用一次 API，支持 offset 分页。
     *
     * @return 总插入条数
     */
    public int fetchRange(String startDate, String endDate) {
        return fetchRange(startDate, endDate, (String) null);
    }

    /**
     * 按日期范围拉取公告，支持 Tushare title LIKE 过滤（如 {@code %年度报告%}）。
     *
     * @param startDate   开始日期 YYYYMMDD
     * @param endDate     结束日期 YYYYMMDD
     * @param titleFilter 标题 LIKE 过滤条件，null 或空则不过滤
     * @return 总插入条数
     */
    public int fetchRange(String startDate, String endDate, String titleFilter) {
        LocalDate start = LocalDate.parse(startDate, DATE_FMT);
        LocalDate end = LocalDate.parse(endDate, DATE_FMT);
        int total = 0;

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String dateStr = date.format(DATE_FMT);
            try {
                total += fetchSingleDay(dateStr, titleFilter);
            } catch (Exception e) {
                log.error("[RagAnnouncementFetchJob] 抓取 date={} 失败: {}", dateStr, e.getMessage(), e);
            }
        }
        return total;
    }

    private int fetchSingleDay(String dateStr, String titleFilter) {
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
            apiParams.put("limit", PAGE_LIMIT);
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

            int inserted = announcementDao.batchUpsert(records);
            totalInserted += inserted;
            log.info("[RagAnnouncementFetchJob] date={} offset={} fetched={} inserted={}",
                    dateStr, offset, items.size(), inserted);

            if (items.size() < PAGE_LIMIT) break;
            offset += items.size();
        }

        log.info("[RagAnnouncementFetchJob] date={} titleFilter={} totalInserted={}", dateStr, titleFilter, totalInserted);
        return totalInserted;
    }
}

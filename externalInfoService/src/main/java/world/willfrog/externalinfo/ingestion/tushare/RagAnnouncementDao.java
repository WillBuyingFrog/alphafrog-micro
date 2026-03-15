package world.willfrog.externalinfo.ingestion.tushare;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

@Repository
@Slf4j
public class RagAnnouncementDao {

    private final JdbcTemplate jdbcTemplate;

    public RagAnnouncementDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 批量 upsert 公告记录（ON CONFLICT DO NOTHING）。
     *
     * @param records 每条记录为 [ann_date, ts_code, name, title, url, rec_time]，ann_date 为 YYYYMMDD 字符串
     */
    public int batchUpsert(List<List<String>> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        String sql = """
                INSERT INTO alphafrog_rag_announcement
                    (ts_code, company_name, ann_date, title, url, rec_time)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (ts_code, ann_date, title) DO NOTHING
                """;
        int inserted = 0;
        for (List<String> row : records) {
            if (row == null || row.size() < 6) {
                continue;
            }
            // fields order: ann_date(0), ts_code(1), name(2), title(3), url(4), rec_time(5)
            String annDateStr = row.get(0);
            String tsCode = row.get(1);
            String name = row.get(2);
            String title = row.get(3);
            String url = row.get(4);
            String recTime = row.get(5);
            long annDateMs = parseDateToMs(annDateStr);
            try {
                int affected = jdbcTemplate.update(sql, tsCode, name, annDateMs, title, url, recTime);
                inserted += affected;
            } catch (Exception e) {
                log.warn("Failed to insert announcement: ts_code={}, ann_date={}, title={}: {}",
                        tsCode, annDateStr, title, e.getMessage());
            }
        }
        return inserted;
    }

    /**
     * 查询某日期是否已有记录（用于增量判断）。
     *
     * @param annDate YYYYMMDD 格式字符串
     */
    public int countByDate(String annDate) {
        long annDateMs = parseDateToMs(annDate);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alphafrog_rag_announcement WHERE ann_date = ?",
                Integer.class, annDateMs);
        return count == null ? 0 : count;
    }

    /**
     * 将 YYYYMMDD 字符串转换为 Asia/Shanghai 00:00:00 毫秒时间戳。
     * 与项目 DateConvertUtils.convertDateStrToLong 保持一致。
     */
    private static long parseDateToMs(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.isBlank()) return -1L;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
            fmt.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            return fmt.parse(yyyymmdd).getTime();
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * 查询待处理（vectorized=FALSE 且 oss_url IS NULL）的记录。
     */
    public List<Map<String, Object>> findUnprocessed(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT id, ts_code, ann_date, title, url FROM alphafrog_rag_announcement " +
                        "WHERE vectorized = FALSE AND oss_url IS NULL ORDER BY id LIMIT ?",
                limit);
    }
}

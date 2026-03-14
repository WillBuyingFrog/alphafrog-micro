package world.willfrog.externalinfo.ingestion.tushare;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

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
     * @param records 每条记录为 [ann_date, ts_code, name, title, url, rec_time]
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
            String annDate = row.get(0);
            String tsCode = row.get(1);
            String name = row.get(2);
            String title = row.get(3);
            String url = row.get(4);
            String recTime = row.get(5);
            try {
                int affected = jdbcTemplate.update(sql, tsCode, name, annDate, title, url, recTime);
                inserted += affected;
            } catch (Exception e) {
                log.warn("Failed to insert announcement: ts_code={}, ann_date={}, title={}: {}",
                        tsCode, annDate, title, e.getMessage());
            }
        }
        return inserted;
    }

    /**
     * 查询某日期是否已有记录（用于增量判断）。
     */
    public int countByDate(String annDate) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alphafrog_rag_announcement WHERE ann_date = ?",
                Integer.class, annDate);
        return count == null ? 0 : count;
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

package world.willfrog.alphafrogmicro.domestic.fetch.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 研报元数据 DAO，负责写入 alphafrog_rag_research_report 表。
 * 从 externalInfoService 迁移至 domesticFetchService。
 */
@Repository
@Slf4j
public class RagResearchReportDao {

    private final JdbcTemplate jdbcTemplate;

    public RagResearchReportDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 批量 upsert 研报记录（ON CONFLICT DO NOTHING）。
     *
     * @param records 每条记录为 [trade_date, title, abstr, report_type, author, name, ts_code, inst_csname, ind_name, url]
     */
    public int batchUpsert(List<List<String>> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        String sql = """
                INSERT INTO alphafrog_rag_research_report
                    (trade_date, title, abstr, report_type, author, stock_name, ts_code, inst_csname, ind_name, url)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (trade_date, title, inst_csname) DO NOTHING
                """;
        int inserted = 0;
        for (List<String> row : records) {
            if (row == null || row.size() < 10) {
                continue;
            }
            // 字段顺序：trade_date(0), title(1), abstr(2), report_type(3), author(4),
            //           name(5), ts_code(6), inst_csname(7), ind_name(8), url(9)
            String tradeDateStr = row.get(0);
            String title = row.get(1);
            String abstr = row.get(2);
            String reportType = row.get(3);
            String author = row.get(4);
            String stockName = row.get(5);
            String tsCode = row.get(6);
            String instCsname = row.get(7);
            String indName = row.get(8);
            String url = row.get(9);
            long tradeDateMs = parseDateToMs(tradeDateStr);
            try {
                int affected = jdbcTemplate.update(sql,
                        tradeDateMs, title, abstr, reportType, author,
                        stockName, tsCode, instCsname, indName, url);
                inserted += affected;
            } catch (Exception e) {
                log.warn("插入研报失败: trade_date={}, title={}, inst={}: {}",
                        tradeDateStr, title, instCsname, e.getMessage());
            }
        }
        return inserted;
    }

    /**
     * 查询某日期是否已有记录（用于增量判断）。
     *
     * @param tradeDate YYYYMMDD 格式字符串
     */
    public int countByDate(String tradeDate) {
        long tradeDateMs = parseDateToMs(tradeDate);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alphafrog_rag_research_report WHERE trade_date = ?",
                Integer.class, tradeDateMs);
        return count == null ? 0 : count;
    }

    /**
     * 查询待处理（vectorized=FALSE 且 oss_url IS NULL）的记录。
     */
    public List<Map<String, Object>> findUnprocessed(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT id, ts_code, trade_date, title, abstr, url FROM alphafrog_rag_research_report " +
                        "WHERE vectorized = FALSE AND oss_url IS NULL ORDER BY id LIMIT ?",
                limit);
    }

    /**
     * 将 YYYYMMDD 字符串转换为 Asia/Shanghai 00:00:00 毫秒时间戳。
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
}

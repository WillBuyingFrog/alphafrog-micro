package world.willfrog.externalinfo.ingestion.tushare;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

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
            // fields order: trade_date(0), title(1), abstr(2), report_type(3), author(4),
            //               name(5), ts_code(6), inst_csname(7), ind_name(8), url(9)
            String tradeDate = row.get(0);
            String title = row.get(1);
            String abstr = row.get(2);
            String reportType = row.get(3);
            String author = row.get(4);
            String stockName = row.get(5);
            String tsCode = row.get(6);
            String instCsname = row.get(7);
            String indName = row.get(8);
            String url = row.get(9);
            try {
                int affected = jdbcTemplate.update(sql,
                        tradeDate, title, abstr, reportType, author,
                        stockName, tsCode, instCsname, indName, url);
                inserted += affected;
            } catch (Exception e) {
                log.warn("Failed to insert research report: trade_date={}, title={}, inst={}: {}",
                        tradeDate, title, instCsname, e.getMessage());
            }
        }
        return inserted;
    }

    /**
     * 查询某日期是否已有记录（用于增量判断）。
     */
    public int countByDate(String tradeDate) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alphafrog_rag_research_report WHERE trade_date = ?",
                Integer.class, tradeDate);
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
}

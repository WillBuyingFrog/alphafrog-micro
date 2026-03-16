"""
PostgreSQL 客户端：查询待处理记录 + 更新 oss_url / vectorized 状态。
"""
import psycopg2
import psycopg2.extras

from config import Config


class DbClient:
    def __init__(self, cfg: Config):
        self.dsn = cfg.db_dsn

    def _conn(self):
        return psycopg2.connect(self.dsn)

    # ── 公告 ────────────────────────────────────────────────
    def get_unprocessed_announcements(self, limit: int = 50):
        """查询 vectorized=FALSE 且 oss_url IS NULL 的公告记录。"""
        with self._conn() as conn, conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(
                "SELECT id, ts_code, ann_date, title, url "
                "FROM alphafrog_rag_announcement "
                "WHERE vectorized = FALSE AND oss_url IS NULL "
                "ORDER BY id LIMIT %s",
                (limit,),
            )
            return cur.fetchall()

    def update_announcement_oss_url(self, record_id: int, oss_url: str):
        """更新公告记录的 oss_url。"""
        with self._conn() as conn, conn.cursor() as cur:
            cur.execute(
                "UPDATE alphafrog_rag_announcement SET oss_url = %s WHERE id = %s",
                (oss_url, record_id),
            )
            conn.commit()

    def mark_announcement_vectorized(self, record_id: int):
        """标记公告记录 vectorized = TRUE。"""
        with self._conn() as conn, conn.cursor() as cur:
            cur.execute(
                "UPDATE alphafrog_rag_announcement SET vectorized = TRUE WHERE id = %s",
                (record_id,),
            )
            conn.commit()

    # ── 研报 ────────────────────────────────────────────────
    def get_unprocessed_reports(self, limit: int = 50):
        """查询 vectorized=FALSE 且 oss_url IS NULL 的研报记录。"""
        with self._conn() as conn, conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(
                "SELECT id, ts_code, trade_date, title, abstr, url "
                "FROM alphafrog_rag_research_report "
                "WHERE vectorized = FALSE AND oss_url IS NULL "
                "ORDER BY id LIMIT %s",
                (limit,),
            )
            return cur.fetchall()

    def update_report_oss_url(self, record_id: int, oss_url: str):
        """更新研报记录的 oss_url。"""
        with self._conn() as conn, conn.cursor() as cur:
            cur.execute(
                "UPDATE alphafrog_rag_research_report SET oss_url = %s WHERE id = %s",
                (oss_url, record_id),
            )
            conn.commit()

    def mark_report_vectorized(self, record_id: int):
        """标记研报记录 vectorized = TRUE。"""
        with self._conn() as conn, conn.cursor() as cur:
            cur.execute(
                "UPDATE alphafrog_rag_research_report SET vectorized = TRUE WHERE id = %s",
                (record_id,),
            )
            conn.commit()

"""
PostgreSQL 客户端：查询待处理记录 + 更新 oss_url / vectorized 状态。
"""
from datetime import datetime, timezone, timedelta

import psycopg2
import psycopg2.extras

from config import Config

# DB 中 ann_date / trade_date 以 Asia/Shanghai（UTC+8）午夜的 Unix 毫秒时间戳存储，
# 与 Java 侧 RagAnnouncementDao.parseDateToMs 保持一致。
_CST = timezone(timedelta(hours=8))


def _yyyymmdd_to_ms(date_str: str) -> int:
    """将 YYYYMMDD 日期字符串转为对应 Asia/Shanghai 午夜的 Unix 毫秒时间戳。"""
    dt = datetime.strptime(date_str, "%Y%m%d").replace(tzinfo=_CST)
    return int(dt.timestamp() * 1000)


class DbClient:
    def __init__(self, cfg: Config):
        self.dsn = cfg.db_dsn

    def _conn(self):
        return psycopg2.connect(self.dsn)

    # ── 公告 ────────────────────────────────────────────────
    def get_unprocessed_announcements(
        self,
        limit: int = 50,
        offset: int = 0,
        date_from: str = None,
        date_to: str = None,
        ts_code: str = None,
    ):
        """查询 vectorized=FALSE 且 oss_url IS NULL 的公告记录。

        可选过滤：ann_date 起止（YYYYMMDD 字符串）、ts_code 精确匹配。
        """
        conditions = ["vectorized = FALSE", "oss_url IS NULL"]
        params: list = []
        if date_from:
            conditions.append("ann_date >= %s")
            params.append(_yyyymmdd_to_ms(date_from))
        if date_to:
            # 包含 date_to 当天：使用下一天午夜作为上界（严格小于）
            conditions.append("ann_date < %s")
            params.append(_yyyymmdd_to_ms(date_to) + 86_400_000)
        if ts_code:
            conditions.append("ts_code = %s")
            params.append(ts_code)
        where = " AND ".join(conditions)
        params.extend([limit, offset])
        sql = (
            f"SELECT id, ts_code, ann_date, title, url "
            f"FROM alphafrog_rag_announcement "
            f"WHERE {where} ORDER BY id LIMIT %s OFFSET %s"
        )
        with self._conn() as conn, conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(sql, params)
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
    def get_unprocessed_reports(
        self,
        limit: int = 50,
        offset: int = 0,
        date_from: str = None,
        date_to: str = None,
        ts_code: str = None,
    ):
        """查询 vectorized=FALSE 且 oss_url IS NULL 的研报记录。

        可选过滤：trade_date 起止（YYYYMMDD 字符串）、ts_code 精确匹配。
        """
        conditions = ["vectorized = FALSE", "oss_url IS NULL"]
        params: list = []
        if date_from:
            conditions.append("trade_date >= %s")
            params.append(_yyyymmdd_to_ms(date_from))
        if date_to:
            # 包含 date_to 当天：使用下一天午夜作为上界（严格小于）
            conditions.append("trade_date < %s")
            params.append(_yyyymmdd_to_ms(date_to) + 86_400_000)
        if ts_code:
            conditions.append("ts_code = %s")
            params.append(ts_code)
        where = " AND ".join(conditions)
        params.extend([limit, offset])
        sql = (
            f"SELECT id, ts_code, trade_date, title, abstr, url "
            f"FROM alphafrog_rag_research_report "
            f"WHERE {where} ORDER BY id LIMIT %s OFFSET %s"
        )
        with self._conn() as conn, conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(sql, params)
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

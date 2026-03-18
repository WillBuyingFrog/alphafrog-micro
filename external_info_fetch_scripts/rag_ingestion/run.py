#!/usr/bin/env python3
"""
RAG Ingestion 主入口脚本。

流程：
1. 从 DB 查 vectorized=FALSE 且 oss_url IS NULL 的记录（公告/研报）
2. 对每条记录：
   a. 用 Jina Reader API 抓取 URL → Markdown 全文
      （公告为 cninfo 页面，研报为 PDF 直链；无 URL 的研报退回到摘要字段）
   b. POST 内容给服务端 /rag/upload-doc，服务端经 VPC 内网上传到 OSS，返回 object key
   c. 更新 DB：oss_url（存储 object key）
   d. 全文切块 → embedding → 调 ingestion 端点写入 Qdrant
   e. 更新 DB：vectorized = TRUE

用法（传统 CLI 模式）：
  python run.py --doc-type ann --limit 10
  python run.py --doc-type research --limit 20
  python run.py --doc-type all --limit 50

用法（YAML 任务配置模式）：
  python run.py --task-config rag-ingestion-tasks.yml
  # 每个 task 可独立指定 doc_type、date_from/date_to、ts_code、limit、offset
"""
import argparse
import sys

import yaml
from dotenv import load_dotenv
from tqdm import tqdm

from config import load_config
from db_client import DbClient
from jina_reader import crawl_url
from oss_uploader import upload_doc
from chunker import chunk_text
from embedder import get_embeddings
from ingest_client import ingest_vectors


def process_announcements(
    db: DbClient,
    cfg,
    limit: int,
    offset: int = 0,
    date_from: str = None,
    date_to: str = None,
    ts_code: str = None,
    title_patterns: list = None,
):
    records = db.get_unprocessed_announcements(
        limit=limit,
        offset=offset,
        date_from=date_from or None,
        date_to=date_to or None,
        ts_code=ts_code or None,
        title_patterns=title_patterns or None,
    )
    if not records:
        print("[run] No unprocessed announcements found.")
        return

    print(f"[run] Processing {len(records)} announcements...")
    for rec in tqdm(records, desc="Announcements"):
        record_id = rec["id"]
        ts_code_val = rec["ts_code"]
        ann_date = rec["ann_date"]
        title = rec["title"]
        url = rec["url"]

        try:
            print(f"  [ann] id={record_id} ts_code={ts_code_val} ann_date={ann_date}")
            print(f"        title={title!r}")
            if not url:
                print(f"  [skip] id={record_id} no url")
                continue
            print(f"        url={url}")

            # a. Jina 爬取公告页面 → Markdown
            markdown_text = crawl_url(url, cfg)
            if not markdown_text.strip():
                print(f"  [skip] id={record_id} empty content from Jina")
                continue
            print(f"  [jina] id={record_id} content_len={len(markdown_text)} chars")

            # b. 上传 OSS（通过服务端中转）
            oss_url = upload_doc(cfg, "ann", ts_code_val, ann_date, title, markdown_text)
            print(f"  [oss]  id={record_id} ossKey={oss_url}")

            # c. 更新 DB oss_url
            db.update_announcement_oss_url(record_id, oss_url)

            # d. 切块 + embedding + ingest
            chunks = chunk_text(markdown_text)
            if chunks:
                print(f"  [chunk] id={record_id} chunks={len(chunks)}")
                embeddings = get_embeddings(chunks, cfg)
                print(f"  [embed] id={record_id} embeddings={len(embeddings)} dim={len(embeddings[0]) if embeddings else 0}")
                metadata = {
                    "doc_type": "announcement",
                    "ts_code": ts_code_val,
                    "ann_date": ann_date,
                    "title": title,
                    "oss_url": oss_url,
                }
                success = ingest_vectors(
                    cfg,
                    doc_id=f"ann_{record_id}",
                    doc_type="announcement",
                    chunks=chunks,
                    embeddings=embeddings,
                    metadata=metadata,
                )
                if success:
                    db.mark_announcement_vectorized(record_id)
                    print(f"  [ok]   id={record_id} done (chunks={len(chunks)})")
                else:
                    print(f"  [fail] id={record_id} ingest failed")
            else:
                print(f"  [skip] id={record_id} no chunks after chunking")

        except Exception as e:
            print(f"  [error] id={record_id}: {e}")


def process_reports(
    db: DbClient,
    cfg,
    limit: int,
    offset: int = 0,
    date_from: str = None,
    date_to: str = None,
    ts_code: str = None,
    title_patterns: list = None,
):
    records = db.get_unprocessed_reports(
        limit=limit,
        offset=offset,
        date_from=date_from or None,
        date_to=date_to or None,
        ts_code=ts_code or None,
        title_patterns=title_patterns or None,
    )
    if not records:
        print("[run] No unprocessed research reports found.")
        return

    print(f"[run] Processing {len(records)} research reports...")
    for rec in tqdm(records, desc="Reports"):
        record_id = rec["id"]
        ts_code_val = rec.get("ts_code", "")
        trade_date = rec["trade_date"]
        title = rec["title"]
        abstr = rec.get("abstr", "")
        url = rec.get("url", "")

        try:
            print(f"  [rep] id={record_id} ts_code={ts_code_val} trade_date={trade_date}")
            print(f"        title={title!r}")
            markdown_text = ""
            used_abstract_fallback = False

            # a. 优先用 Jina 爬取 PDF/页面
            if url:
                print(f"        url={url}")
                try:
                    markdown_text = crawl_url(url, cfg)
                    print(f"  [jina] id={record_id} content_len={len(markdown_text)} chars")
                except Exception as crawl_err:
                    print(f"  [warn] id={record_id} crawl failed: {crawl_err}")

            # 退回到摘要
            if not markdown_text.strip() and abstr:
                markdown_text = abstr
                used_abstract_fallback = True
                print(f"  [fallback] id={record_id} using abstract ({len(abstr)} chars)")

            if not markdown_text.strip():
                print(f"  [skip] id={record_id} no content")
                continue

            # b. 上传 OSS（通过服务端中转）
            oss_url = upload_doc(
                cfg, "research", ts_code_val or "", trade_date, title, markdown_text,
                file_extension=".txt" if used_abstract_fallback else ".md",
            )
            print(f"  [oss]  id={record_id} ossKey={oss_url}")

            # c. 更新 DB oss_url
            db.update_report_oss_url(record_id, oss_url)

            # d. 切块 + embedding + ingest
            chunks = chunk_text(markdown_text)
            if chunks:
                print(f"  [chunk] id={record_id} chunks={len(chunks)}")
                embeddings = get_embeddings(chunks, cfg)
                print(f"  [embed] id={record_id} embeddings={len(embeddings)} dim={len(embeddings[0]) if embeddings else 0}")
                metadata = {
                    "doc_type": "research_report",
                    "ts_code": ts_code_val or "",
                    "trade_date": trade_date,
                    "title": title,
                    "oss_url": oss_url,
                }
                success = ingest_vectors(
                    cfg,
                    doc_id=f"report_{record_id}",
                    doc_type="research_report",
                    chunks=chunks,
                    embeddings=embeddings,
                    metadata=metadata,
                )
                if success:
                    db.mark_report_vectorized(record_id)
                    print(f"  [ok]   id={record_id} done (chunks={len(chunks)})")
                else:
                    print(f"  [fail] id={record_id} ingest failed")
            else:
                print(f"  [skip] id={record_id} no chunks after chunking")

        except Exception as e:
            print(f"  [error] id={record_id}: {e}")


def run_task(task: dict, db: DbClient, cfg):
    """执行单个 YAML task 配置。"""
    name = task.get("name", "(未命名)")
    doc_type = task.get("doc_type", "all")
    date_from = str(task["date_from"]) if task.get("date_from") else None
    date_to = str(task["date_to"]) if task.get("date_to") else None
    ts_code = task.get("ts_code") or None
    limit = int(task.get("limit", 50))
    offset = int(task.get("offset", 0))
    title_patterns = task.get("title_patterns") or None

    print(f"\n{'='*60}")
    print(f"[task] {name!r}  doc_type={doc_type}  date_from={date_from}  date_to={date_to}")
    print(f"       ts_code={ts_code}  limit={limit}  offset={offset}  title_patterns={title_patterns}")
    print(f"{'='*60}")

    if doc_type in ("ann", "all"):
        process_announcements(db, cfg, limit=limit, offset=offset,
                              date_from=date_from, date_to=date_to, ts_code=ts_code,
                              title_patterns=title_patterns)
    if doc_type in ("research", "all"):
        process_reports(db, cfg, limit=limit, offset=offset,
                        date_from=date_from, date_to=date_to, ts_code=ts_code,
                        title_patterns=title_patterns)


def run_from_task_config(task_config_path: str, db: DbClient, cfg):
    """从 YAML 任务配置文件加载并依次执行所有 enabled task。"""
    with open(task_config_path, "r", encoding="utf-8") as f:
        config = yaml.safe_load(f)

    tasks = config.get("tasks", [])
    if not tasks:
        print("[run] task config 中没有任何 task，退出。")
        return

    enabled = [t for t in tasks if t.get("enabled", True)]
    print(f"[run] 共 {len(tasks)} 个 task，其中 {len(enabled)} 个已启用。")

    for i, task in enumerate(enabled, 1):
        print(f"\n[run] ── Task {i}/{len(enabled)} ──")
        run_task(task, db, cfg)

    print("\n[run] 所有 task 执行完毕。")


def main():
    parser = argparse.ArgumentParser(description="RAG Ingestion Script")
    # YAML 任务配置模式（优先）
    parser.add_argument(
        "--task-config",
        metavar="FILE",
        help="YAML 任务配置文件路径；指定后忽略 --doc-type / --limit / --offset",
    )
    # 传统 CLI 模式（向后兼容）
    parser.add_argument(
        "--doc-type",
        choices=["ann", "research", "all"],
        default="all",
        help="文档类型（默认 all），仅在未指定 --task-config 时生效",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=50,
        help="每种类型最多处理条数（默认 50），仅在未指定 --task-config 时生效",
    )
    parser.add_argument(
        "--offset",
        type=int,
        default=0,
        help="DB 查询偏移量（默认 0），仅在未指定 --task-config 时生效",
    )
    parser.add_argument(
        "--date-from",
        metavar="YYYYMMDD",
        default=None,
        help="起始日期过滤（含），仅在未指定 --task-config 时生效",
    )
    parser.add_argument(
        "--date-to",
        metavar="YYYYMMDD",
        default=None,
        help="截止日期过滤（含），仅在未指定 --task-config 时生效",
    )
    parser.add_argument(
        "--ts-code",
        metavar="CODE",
        default=None,
        help="股票代码精确过滤（如 000001.SZ），仅在未指定 --task-config 时生效",
    )
    parser.add_argument(
        "--title-pattern",
        metavar="PATTERN",
        action="append",
        dest="title_pattern",
        default=None,
        help="title 子串过滤（可重复指定，OR 关系），仅在未指定 --task-config 时生效。例：--title-pattern 年度公告 --title-pattern 年度报告",
    )
    args = parser.parse_args()

    load_dotenv()
    cfg = load_config()
    db = DbClient(cfg)

    if args.task_config:
        run_from_task_config(args.task_config, db, cfg)
    else:
        # 传统 CLI 模式
        if args.doc_type in ("ann", "all"):
            process_announcements(
                db, cfg,
                limit=args.limit,
                offset=args.offset,
                date_from=args.date_from,
                date_to=args.date_to,
                ts_code=args.ts_code,
                title_patterns=args.title_pattern,
            )
        if args.doc_type in ("research", "all"):
            process_reports(
                db, cfg,
                limit=args.limit,
                offset=args.offset,
                date_from=args.date_from,
                date_to=args.date_to,
                ts_code=args.ts_code,
                title_patterns=args.title_pattern,
            )
        print("[run] Done.")


if __name__ == "__main__":
    main()

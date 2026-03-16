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

用法：
  python run.py --doc-type ann --limit 10
  python run.py --doc-type research --limit 20
  python run.py --doc-type all --limit 50
"""
import argparse

from dotenv import load_dotenv
from tqdm import tqdm

from config import load_config
from db_client import DbClient
from jina_reader import crawl_url
from oss_uploader import upload_doc
from chunker import chunk_text
from embedder import get_embeddings
from ingest_client import ingest_vectors


def process_announcements(db: DbClient, cfg, limit: int):
    records = db.get_unprocessed_announcements(limit)
    if not records:
        print("[run] No unprocessed announcements found.")
        return

    print(f"[run] Processing {len(records)} announcements...")
    for rec in tqdm(records, desc="Announcements"):
        record_id = rec["id"]
        ts_code = rec["ts_code"]
        ann_date = rec["ann_date"]
        title = rec["title"]
        url = rec["url"]

        try:
            print(f"  [ann] id={record_id} ts_code={ts_code} ann_date={ann_date}")
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
            oss_url = upload_doc(cfg, "ann", ts_code, ann_date, title, markdown_text)
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
                    "ts_code": ts_code,
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


def process_reports(db: DbClient, cfg, limit: int):
    records = db.get_unprocessed_reports(limit)
    if not records:
        print("[run] No unprocessed research reports found.")
        return

    print(f"[run] Processing {len(records)} research reports...")
    for rec in tqdm(records, desc="Reports"):
        record_id = rec["id"]
        ts_code = rec.get("ts_code", "")
        trade_date = rec["trade_date"]
        title = rec["title"]
        abstr = rec.get("abstr", "")
        url = rec.get("url", "")

        try:
            print(f"  [rep] id={record_id} ts_code={ts_code} trade_date={trade_date}")
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
                cfg, "research", ts_code or "", trade_date, title, markdown_text,
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
                    "ts_code": ts_code or "",
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


def main():
    parser = argparse.ArgumentParser(description="RAG Ingestion Script")
    parser.add_argument(
        "--doc-type",
        choices=["ann", "research", "all"],
        default="all",
        help="Document type to process (default: all)",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=50,
        help="Max number of records to process per type (default: 50)",
    )
    args = parser.parse_args()

    load_dotenv()
    cfg = load_config()
    db = DbClient(cfg)

    if args.doc_type in ("ann", "all"):
        process_announcements(db, cfg, args.limit)
    if args.doc_type in ("research", "all"):
        process_reports(db, cfg, args.limit)

    print("[run] Done.")


if __name__ == "__main__":
    main()

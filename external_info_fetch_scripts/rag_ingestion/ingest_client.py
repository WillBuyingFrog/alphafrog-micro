"""
调 externalInfoService HTTP ingestion 端点，写入 Qdrant。
"""
from typing import List

import httpx

from config import Config


def ingest_vectors(
    cfg: Config,
    doc_id: str,
    doc_type: str,           # "announcement" | "research_report"
    chunks: List[str],
    embeddings: List[List[float]],
    metadata: dict,
) -> bool:
    """
    将 chunk + embedding 发送到 externalInfoService ingestion 端点。
    返回是否成功。
    """
    url = cfg.ingest_endpoint.rstrip("/")
    headers = {
        "Authorization": f"Bearer {cfg.ingest_admin_token}",
        "Content-Type": "application/json",
    }
    payload = {
        "docId": doc_id,
        "docType": doc_type,
        "chunks": [
            {
                "text": chunk,
                "embedding": embedding,
                "metadata": metadata,
            }
            for chunk, embedding in zip(chunks, embeddings)
        ],
    }

    try:
        resp = httpx.post(url, json=payload, headers=headers, timeout=120.0)
        resp.raise_for_status()
        return True
    except Exception as e:
        print(f"[ingest_client] Failed to ingest doc_id={doc_id}: {e}")
        return False

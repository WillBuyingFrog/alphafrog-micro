"""
调 OpenAI 兼容 embedding API（通过 OpenRouter 或其他 provider）。
"""
from typing import List

import httpx

from config import Config


def get_embeddings(
    texts: List[str], cfg: Config
) -> List[List[float]]:
    """
    批量获取 embedding 向量。
    使用 OpenAI 兼容格式：POST /embeddings。
    """
    if not texts:
        return []

    url = f"{cfg.embedding_base_url.rstrip('/')}/embeddings"
    headers = {
        "Authorization": f"Bearer {cfg.embedding_api_key}",
        "Content-Type": "application/json",
    }
    payload = {
        "model": cfg.embedding_model,
        "input": texts,
    }
    if cfg.embedding_dim:
        payload["dimensions"] = cfg.embedding_dim

    resp = httpx.post(url, json=payload, headers=headers, timeout=60.0)
    resp.raise_for_status()
    data = resp.json()["data"]
    # 按 index 排序后返回
    data.sort(key=lambda x: x["index"])
    return [item["embedding"] for item in data]

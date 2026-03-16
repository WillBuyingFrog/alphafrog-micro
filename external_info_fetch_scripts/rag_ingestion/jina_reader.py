"""
通过 Jina Reader API 将任意 URL（网页或 PDF）转换为 Markdown 文本。
API 文档：https://jina.ai/reader/
用法：GET https://r.jina.ai/{目标URL}，可选 Bearer token 提升限速额度。
"""
import httpx

from config import Config

JINA_READER_BASE = "https://r.jina.ai/"


def crawl_url(url: str, cfg: Config, timeout: float = 60.0) -> str:
    """
    使用 Jina Reader API 抓取指定 URL，返回 Markdown 全文。
    支持网页（如 cninfo 公告页）和 PDF 直链。
    """
    headers = {"Accept": "text/plain"}
    if cfg.jina_api_key:
        headers["Authorization"] = f"Bearer {cfg.jina_api_key}"

    resp = httpx.get(
        JINA_READER_BASE + url,
        headers=headers,
        timeout=timeout,
        follow_redirects=True,
    )
    resp.raise_for_status()
    return resp.text

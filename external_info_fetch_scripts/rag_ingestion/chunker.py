"""
全文切块：字符级滑动窗口（适合中文，无需按词边界）。
"""
from typing import List


def chunk_text(
    text: str, chunk_size: int = 500, overlap: int = 50
) -> List[str]:
    """
    字符级滑动窗口切块。
    chunk_size=500 约等于一段正文，overlap=50 保持上下文连贯性。
    """
    if not text.strip():
        return []
    chunks: List[str] = []
    start = 0
    while start < len(text):
        end = min(start + chunk_size, len(text))
        chunk = text[start:end].strip()
        if chunk:
            chunks.append(chunk)
        if end >= len(text):
            break
        start = end - overlap
    return chunks

import os
from dataclasses import dataclass, field


@dataclass
class Config:
    # PostgreSQL
    db_dsn: str

    # Jina Reader API（爬取网页/PDF → Markdown）
    jina_api_key: str              # https://jina.ai/reader/ 申请，免费 key 500 RPM

    # Embedding API（OpenRouter，OpenAI 兼容格式）
    embedding_base_url: str
    embedding_api_key: str
    embedding_model: str
    embedding_dim: int

    # frontend 对外暴露的 RAG ingestion 端点
    ingest_endpoint: str
    ingest_admin_token: str

    # OSS 文档上传端点（服务端上传，本地只发 HTTP 请求）
    upload_doc_endpoint: str

    # ── 以下字段预留给未来多模态 RAG，当前流程不使用 ──────────────
    # PDF OCR provider（"baidu" | "aliyun"）
    pdf_parser_provider: str = ""
    baidu_doc_parser_url: str = ""
    baidu_doc_parser_token: str = ""
    aliyun_doc_parser_endpoint: str = ""
    aliyun_access_key_id: str = ""
    aliyun_access_key_secret: str = ""


def load_config() -> Config:
    ingest_endpoint = os.environ["INGEST_ENDPOINT"]
    upload_doc_endpoint = os.environ.get(
        "UPLOAD_DOC_ENDPOINT",
        ingest_endpoint.replace("/rag/ingest", "/rag/upload-doc"),
    )

    return Config(
        db_dsn=os.environ["PG_DSN"],

        jina_api_key=os.environ.get("JINA_API_KEY", ""),

        embedding_base_url=os.environ.get("EMBEDDING_BASE_URL",
                                          "https://openrouter.ai/api/v1"),
        embedding_api_key=os.environ["EMBEDDING_API_KEY"],
        embedding_model=os.environ.get("EMBEDDING_MODEL",
                                       "openai/text-embedding-3-small"),
        embedding_dim=int(os.environ.get("EMBEDDING_DIM", "1024")),

        ingest_endpoint=ingest_endpoint,
        ingest_admin_token=os.environ["INGEST_ADMIN_TOKEN"],

        upload_doc_endpoint=upload_doc_endpoint,

        # OCR 相关（可选，当前不使用）
        pdf_parser_provider=os.environ.get("PDF_PARSER_PROVIDER", ""),
        baidu_doc_parser_url=os.environ.get("BAIDU_DOC_PARSER_URL", ""),
        baidu_doc_parser_token=os.environ.get("BAIDU_DOC_PARSER_TOKEN", ""),
        aliyun_doc_parser_endpoint=os.environ.get("ALIYUN_DOC_PARSER_ENDPOINT", ""),
        aliyun_access_key_id=os.environ.get(
            "ALIYUN_ACCESS_KEY_ID",
            os.environ.get("OSS_ACCESS_KEY_ID", "")),
        aliyun_access_key_secret=os.environ.get(
            "ALIYUN_ACCESS_KEY_SECRET",
            os.environ.get("OSS_ACCESS_KEY_SECRET", "")),
    )

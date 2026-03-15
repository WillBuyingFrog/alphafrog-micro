import os
from dataclasses import dataclass


@dataclass
class Config:
    # PostgreSQL
    db_dsn: str

    # Embedding API（OpenRouter，OpenAI 兼容格式）
    embedding_base_url: str    # "https://openrouter.ai/api/v1"
    embedding_api_key: str     # OpenRouter API key
    embedding_model: str       # 例如 "openai/text-embedding-3-small"
    embedding_dim: int         # 1024

    # PDF 解析 provider
    pdf_parser_provider: str   # "baidu" | "aliyun"
    baidu_doc_parser_token: str          # 百度文档解析 API token（provider=baidu 时必填）
    aliyun_doc_parser_endpoint: str      # 阿里云 DocMind endpoint（provider=aliyun 时必填）
    # 阿里云 DocMind 本地调用需要 AK/SK（OSS 上传已移至服务端，但 DocMind 解析仍在本地）
    aliyun_access_key_id: str
    aliyun_access_key_secret: str

    # frontend 对外暴露的 RAG ingestion 端点（frontend 内部转发给 externalInfoService）
    ingest_endpoint: str       # 例如 "http://your-server:8090/rag/ingest"
    ingest_admin_token: str    # 对应服务端 AF_RAG_INGEST_TOKEN 环境变量

    # OSS 文档上传端点（服务端上传，本地只发 HTTP 请求）
    upload_doc_endpoint: str   # 例如 "http://your-server:8090/rag/upload-doc"


def load_config() -> Config:
    ingest_endpoint = os.environ["INGEST_ENDPOINT"]
    # 默认从 INGEST_ENDPOINT 推导 upload-doc 地址，也可通过 UPLOAD_DOC_ENDPOINT 单独配置
    upload_doc_endpoint = os.environ.get(
        "UPLOAD_DOC_ENDPOINT",
        ingest_endpoint.replace("/rag/ingest", "/rag/upload-doc"),
    )

    return Config(
        db_dsn=os.environ["PG_DSN"],

        embedding_base_url=os.environ.get("EMBEDDING_BASE_URL",
                                          "https://openrouter.ai/api/v1"),
        embedding_api_key=os.environ["EMBEDDING_API_KEY"],
        embedding_model=os.environ.get("EMBEDDING_MODEL",
                                       "openai/text-embedding-3-small"),
        embedding_dim=int(os.environ.get("EMBEDDING_DIM", "1024")),

        pdf_parser_provider=os.environ.get("PDF_PARSER_PROVIDER", "baidu"),
        baidu_doc_parser_token=os.environ.get("BAIDU_DOC_PARSER_TOKEN", ""),
        aliyun_doc_parser_endpoint=os.environ.get(
            "ALIYUN_DOC_PARSER_ENDPOINT",
            "docmind-api.cn-hangzhou.aliyuncs.com"),
        # 优先读新变量名，向后兼容旧的 OSS_ACCESS_KEY_ID
        aliyun_access_key_id=os.environ.get(
            "ALIYUN_ACCESS_KEY_ID",
            os.environ.get("OSS_ACCESS_KEY_ID", "")),
        aliyun_access_key_secret=os.environ.get(
            "ALIYUN_ACCESS_KEY_SECRET",
            os.environ.get("OSS_ACCESS_KEY_SECRET", "")),

        ingest_endpoint=ingest_endpoint,
        ingest_admin_token=os.environ["INGEST_ADMIN_TOKEN"],

        upload_doc_endpoint=upload_doc_endpoint,
    )

import os
from dataclasses import dataclass


@dataclass
class Config:
    # PostgreSQL
    db_dsn: str

    # 阿里云 OSS（Python SDK V2 方式，region 代替 endpoint）
    oss_region: str            # 例如 "ap-southeast-1"（新加坡）或 "cn-hangzhou"（杭州）
    oss_bucket: str            # bucket 名称
    oss_access_key_id: str
    oss_access_key_secret: str
    oss_path_prefix: str       # 存储路径前缀，例如 "alphafrog-rag"

    # Embedding API（OpenRouter，OpenAI 兼容格式）
    embedding_base_url: str    # "https://openrouter.ai/api/v1"
    embedding_api_key: str     # OpenRouter API key
    embedding_model: str       # 例如 "openai/text-embedding-3-small"
    embedding_dim: int         # 1024

    # PDF 解析 provider
    pdf_parser_provider: str   # "baidu" | "aliyun"
    baidu_doc_parser_token: str          # 百度文档解析 API token（provider=baidu 时必填）
    aliyun_doc_parser_endpoint: str      # 阿里云 DocMind endpoint（provider=aliyun 时必填）
    # 注意：阿里云 DocMind 复用 OSS 的 access_key_id / access_key_secret，无需额外字段

    # frontend 对外暴露的 RAG ingestion 端点（frontend 内部转发给 externalInfoService）
    ingest_endpoint: str       # 例如 "http://your-server:8090/rag/ingest"
    ingest_admin_token: str    # 对应服务端 AF_RAG_INGEST_TOKEN 环境变量


def load_config() -> Config:
    return Config(
        db_dsn=os.environ["PG_DSN"],

        oss_region=os.environ.get("OSS_REGION", "ap-southeast-1"),
        oss_bucket=os.environ["OSS_BUCKET"],
        oss_access_key_id=os.environ["OSS_ACCESS_KEY_ID"],
        oss_access_key_secret=os.environ["OSS_ACCESS_KEY_SECRET"],
        oss_path_prefix=os.environ.get("OSS_PATH_PREFIX", "alphafrog-rag"),

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

        ingest_endpoint=os.environ["INGEST_ENDPOINT"],
        ingest_admin_token=os.environ["INGEST_ADMIN_TOKEN"],
    )

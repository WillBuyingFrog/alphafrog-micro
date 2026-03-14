"""
上传 Markdown 到阿里云 OSS（alibabacloud-oss-v2），返回公网 URL。
"""
import re

import alibabacloud_oss_v2 as oss

from config import Config


def build_oss_client(cfg: Config) -> oss.Client:
    oss_cfg = oss.config.load_default()
    oss_cfg.credentials_provider = oss.credentials.StaticCredentialsProvider(
        cfg.oss_access_key_id, cfg.oss_access_key_secret
    )
    oss_cfg.region = cfg.oss_region
    return oss.Client(oss_cfg)


def upload_markdown(
    client: oss.Client,
    cfg: Config,
    doc_type: str,      # "ann" | "research"
    ts_code: str,
    date: str,           # YYYYMMDD
    title: str,
    markdown_text: str,
) -> str:
    """上传 Markdown 全文到 OSS，返回公网 URL。"""
    safe_title = re.sub(r"[^\w\u4e00-\u9fff-]", "_", title)[:60]
    key = (
        f"{cfg.oss_path_prefix}/{doc_type}/"
        f"{ts_code or 'no_code'}/{date}_{safe_title}.md"
    )

    client.put_object(
        oss.PutObjectRequest(
            bucket=cfg.oss_bucket,
            key=key,
            body=markdown_text.encode("utf-8"),
            content_type="text/plain; charset=utf-8",
        )
    )
    return f"https://{cfg.oss_bucket}.oss-{cfg.oss_region}.aliyuncs.com/{key}"

"""
PDF 下载 + 云端解析 → Markdown 全文（纯文本，不含图片）。
支持两个 provider：百度文档解析（同步）、阿里云 DocMind（异步 job）。
"""
import base64
import os
import tempfile
import time

import httpx
import requests

from config import Config


def download_pdf(url: str) -> bytes:
    """下载 PDF，超时 90 秒（金融年报可能较大）。"""
    resp = httpx.get(url, timeout=90.0, follow_redirects=True)
    resp.raise_for_status()
    return resp.content


def pdf_bytes_to_markdown(pdf_bytes: bytes, cfg: Config) -> str:
    """
    根据 cfg.pdf_parser_provider 路由到对应 provider。
    返回 Markdown 全文字符串（纯文本，不含图片）。
    """
    if cfg.pdf_parser_provider == "baidu":
        return _parse_baidu(pdf_bytes, cfg)
    elif cfg.pdf_parser_provider == "aliyun":
        return _parse_aliyun(pdf_bytes, cfg)
    else:
        raise ValueError(f"Unknown pdf_parser_provider: {cfg.pdf_parser_provider}")


# ── 百度文档解析（同步调用） ──────────────────────────────────

def _parse_baidu(pdf_bytes: bytes, cfg: Config) -> str:
    file_data = base64.b64encode(pdf_bytes).decode("ascii")
    headers = {
        "Authorization": f"token {cfg.baidu_doc_parser_token}",
        "Content-Type": "application/json",
    }
    payload = {
        "file": file_data,
        "fileType": 0,
        "useDocOrientationClassify": False,
        "useDocUnwarping": False,
        "useChartRecognition": False,
    }
    response = requests.post(
        cfg.baidu_doc_parser_url, json=payload, headers=headers, timeout=120
    )
    if not response.ok:
        raise RuntimeError(
            f"百度文档解析 HTTP {response.status_code}: {response.text[:500]}"
        )
    results = response.json()["result"]["layoutParsingResults"]
    parts = [
        res["markdown"]["text"]
        for res in results
        if res.get("markdown", {}).get("text")
    ]
    return "\n\n".join(parts)


# ── 阿里云 DocMind（异步 job + 轮询） ─────────────────────────

def _parse_aliyun(pdf_bytes: bytes, cfg: Config) -> str:
    from alibabacloud_docmind_api20220711.client import Client as DocMindClient
    from alibabacloud_tea_openapi import models as open_api_models
    from alibabacloud_docmind_api20220711 import models as docmind_models
    from alibabacloud_tea_util import models as util_models

    api_config = open_api_models.Config(
        access_key_id=cfg.aliyun_access_key_id,
        access_key_secret=cfg.aliyun_access_key_secret,
    )
    api_config.endpoint = cfg.aliyun_doc_parser_endpoint
    client = DocMindClient(api_config)

    tmp_path = ""
    try:
        with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as f:
            f.write(pdf_bytes)
            tmp_path = f.name

        # 1. 提交任务
        with open(tmp_path, "rb") as file_obj:
            request = docmind_models.SubmitDocParserJobAdvanceRequest(
                file_url_object=file_obj,
                file_name="doc.pdf",
                file_name_extension="pdf",
                llm_enhancement=True,
                enhancement_mode="VLM",
            )
            runtime = util_models.RuntimeOptions()
            resp = client.submit_doc_parser_job_advance(request, runtime)
        task_id = resp.body.data.id

        # 2. 轮询等待完成
        while True:
            status_resp = client.query_doc_parser_status(
                docmind_models.QueryDocParserStatusRequest(id=task_id)
            )
            status = status_resp.body.data.to_map().get("Status", "").lower()
            if status == "success":
                break
            elif status == "failed":
                raise RuntimeError(
                    f"Aliyun DocMind job failed: task_id={task_id}"
                )
            time.sleep(5)

        # 3. 增量取结果
        layout_num, step = 0, 10
        parts: list[str] = []
        while True:
            result_resp = client.get_doc_parser_result(
                docmind_models.GetDocParserResultRequest(
                    id=task_id,
                    layout_num=layout_num,
                    layout_step_size=step,
                )
            )
            data_map = (
                result_resp.body.data.to_map()
                if result_resp.body.data
                else {}
            )
            layouts = data_map.get("layouts", [])
            if not layouts:
                break
            for layout in layouts:
                text = layout.get("markdownContent", "").strip()
                if text:
                    parts.append(text)
            layout_num += len(layouts)
            if len(layouts) < step:
                break

        return "\n\n".join(parts)
    finally:
        if tmp_path and os.path.exists(tmp_path):
            os.unlink(tmp_path)

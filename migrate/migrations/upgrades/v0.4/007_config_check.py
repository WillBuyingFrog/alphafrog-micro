#!/usr/bin/env python3
"""
v0.4 配置检查脚本
检查以下内容：
1. ADMIN_MAGIC_PASSWORD 环境变量
2. agent-llm.local.json 结构是否为 v0.4 格式（endpoint/models 嵌套）
3. prompts/ 目录是否存在
"""

import json
import os
from pathlib import Path
import sys

ENV_FILE = Path(".env")
CONFIG_FILE = Path("agentService/config/agent-llm.local.json")
EXAMPLE_FILE = Path("agentService/config/agent-llm.local.example.json")
PROMPTS_DIR = Path("agentService/config/prompts")

def check_env():
    """检查 .env 中 ADMIN_MAGIC_PASSWORD"""
    if not ENV_FILE.exists():
        print("[WARN] .env 文件不存在")
        return False

    content = ENV_FILE.read_text(encoding="utf-8")
    if "ADMIN_MAGIC_PASSWORD" in content:
        print("[OK] ADMIN_MAGIC_PASSWORD 已配置")
        return True
    else:
        print("[WARN] ADMIN_MAGIC_PASSWORD 未在 .env 中配置")
        print("       请添加: ADMIN_MAGIC_PASSWORD=your_secure_password")
        return False

def check_agent_llm_config():
    """检查 agent-llm.local.json 结构"""
    if not CONFIG_FILE.exists():
        print(f"[WARN] agent-llm 配置不存在: {CONFIG_FILE}")
        return False

    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            config = json.load(f)
    except Exception as e:
        print(f"[WARN] 无法解析 agent-llm 配置: {e}")
        return False

    # v0.4 格式特征：有 endpoints 和 models 字段
    if "endpoints" in config and "models" in config:
        print("[OK] agent-llm 配置结构符合 v0.4 格式")
        return True
    else:
        print("[WARN] agent-llm 配置结构可能是旧格式（v0.3）")
        print("       v0.4 需要使用 endpoints + models 嵌套结构")
        if EXAMPLE_FILE.exists():
            print(f"       请参考示例文件: {EXAMPLE_FILE}")
        return False

def check_prompts_dir():
    """检查 prompts 目录是否存在"""
    if PROMPTS_DIR.exists():
        print(f"[OK] Prompts 目录已存在: {PROMPTS_DIR}")
        return True
    else:
        print(f"[WARN] Prompts 目录不存在: {PROMPTS_DIR}")
        print("       v0.4 需要外置 prompt 文件，请确保部署时包含 prompts/ 目录")
        return False

def main():
    print("v0.4 配置检查")
    print("-" * 50)

    results = [
        check_env(),
        check_agent_llm_config(),
        check_prompts_dir(),
    ]

    print("-" * 50)
    if all(results):
        print("所有配置检查通过")
        return 0
    else:
        print("部分配置需要手动处理，请根据上方提示操作")
        return 0  # 非阻塞，仅提示

if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""
v0.6 配置检查脚本
检查 agent-llm.local.json 是否包含 v0.6 新增的配置项
"""

import json
from pathlib import Path
import sys

CONFIG_FILE = Path("agentService/config/agent-llm.local.json")
EXAMPLE_FILE = Path("agentService/config/agent-llm.local.example.json")

def main():
    print("v0.6 配置检查")
    print("-" * 50)

    if not CONFIG_FILE.exists():
        print(f"[WARN] agent-llm 配置不存在: {CONFIG_FILE}")
        return 0

    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            config = json.load(f)
    except Exception as e:
        print(f"[WARN] 无法解析 agent-llm 配置: {e}")
        return 0

    # v0.6 新增配置项检查
    v06_features = []

    # 1. per-run stage LLM config (runtime.planning/execution/subAgent)
    runtime = config.get("runtime", {})
    if "planning" in runtime and ("endpointName" in runtime.get("planning", {}) or "modelName" in runtime.get("planning", {})):
        v06_features.append("planning stage config")

    # 2. structuredOutput
    if "structuredOutput" in str(runtime):
        v06_features.append("structured output")

    # 3. debug flags
    debug = config.get("debug", {})
    if "logLlmCurl" in debug or "logStageConfig" in debug:
        v06_features.append("debug flags")

    # 4. openrouter app name
    openrouter = config.get("openrouter", {})
    if "title" in openrouter or "http-referer" in openrouter:
        v06_features.append("openrouter app name")

    # 5. validProviders
    endpoints = config.get("endpoints", {})
    for ep_name, ep_config in endpoints.items():
        models = ep_config.get("models", {})
        for model_name, model_config in models.items():
            if "validProviders" in str(model_config):
                v06_features.append("validProviders")
                break

    v06_features = list(set(v06_features))  # 去重

    if v06_features:
        print(f"[OK] 检测到 v0.6 配置项: {', '.join(v06_features)}")
        return 0
    else:
        print("[INFO] 未检测到 v0.6 新增配置项")
        print("       v0.6 新增了以下配置能力:")
        print("       - per-run stage LLM config (planning/execution/subAgent)")
        print("       - structured output for planning")
        print("       - debug flags (logLlmCurl, logStageConfig)")
        print("       - openrouter app name (title, http-referer)")
        print("       - validProviders for model routing")
        if EXAMPLE_FILE.exists():
            print(f"       请参考示例文件更新配置: {EXAMPLE_FILE}")
        return 0  # 非阻塞，仅提示

if __name__ == "__main__":
    sys.exit(main())

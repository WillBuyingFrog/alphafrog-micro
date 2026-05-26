#!/usr/bin/env python3
"""
v0.3-phase1 配置检查脚本
检查 agent-llm.local.json 是否存在
"""

from pathlib import Path
import sys

CONFIG_FILE = Path("agentService/config/agent-llm.local.json")
EXAMPLE_FILE = Path("agentService/config/agent-llm.local.example.json")

def main():
    if CONFIG_FILE.exists():
        print(f"[OK] agent-llm 配置已存在: {CONFIG_FILE}")
        return 0

    print(f"[WARN] agent-llm 配置不存在: {CONFIG_FILE}")
    if EXAMPLE_FILE.exists():
        print(f"       请从示例文件复制并修改:")
        print(f"       cp {EXAMPLE_FILE} {CONFIG_FILE}")
    else:
        print(f"       示例文件也不存在，请检查 agentService/config/ 目录")
    return 0  # 非阻塞，仅提示

if __name__ == "__main__":
    sys.exit(main())

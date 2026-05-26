#!/usr/bin/env python3
"""
v0.5 环境变量和配置检查脚本
检查以下内容：
1. Kafka 环境变量是否已替换为 RabbitMQ
2. MeiliSearch 环境变量是否存在
3. search-llm 配置是否已迁移至 externalInfoService
4. docker-compose.yml 中是否仍有 Kafka 服务
"""

from pathlib import Path
import sys

ENV_FILE = Path(".env")
DOCKER_COMPOSE = Path("docker-compose.yml")
OLD_SEARCH_LLM = Path("agentService/config/search-llm.local.json")
NEW_SEARCH_LLM = Path("externalInfoService/config/search-llm.local.json")
NEW_SEARCH_EXAMPLE = Path("externalInfoService/config/search-llm.local.example.json")

def check_env():
    """检查环境变量"""
    if not ENV_FILE.exists():
        print("[WARN] .env 文件不存在")
        return False

    content = ENV_FILE.read_text(encoding="utf-8")
    issues = []

    # 检查 Kafka 变量是否仍存在
    if "AF_KAFKA_BOOTSTRAP_SERVERS" in content or "KAFKA" in content.upper().split("\n"):
        if "AF_KAFKA_BOOTSTRAP_SERVERS" in content:
            issues.append("AF_KAFKA_BOOTSTRAP_SERVERS 仍存在，需要替换为 RabbitMQ 变量")

    # 检查 RabbitMQ 变量是否存在
    rabbitmq_vars = ["AF_RABBITMQ_HOST", "AF_RABBITMQ_PORT", "AF_RABBITMQ_USER", "AF_RABBITMQ_PASS"]
    missing_rabbitmq = [v for v in rabbitmq_vars if v not in content]
    if missing_rabbitmq:
        issues.append(f"RabbitMQ 变量缺失: {', '.join(missing_rabbitmq)}")

    # 检查 MeiliSearch 变量
    meili_vars = ["AF_MEILI_HOST", "AF_MEILI_API_KEY"]
    missing_meili = [v for v in meili_vars if v not in content]
    if missing_meili:
        issues.append(f"MeiliSearch 变量缺失: {', '.join(missing_meili)}")

    if issues:
        print("[WARN] 环境变量检查发现问题:")
        for issue in issues:
            print(f"       - {issue}")
        return False
    else:
        print("[OK] 环境变量配置正确（Kafka 已移除，RabbitMQ 和 MeiliSearch 已配置）")
        return True

def check_docker_compose():
    """检查 docker-compose.yml"""
    if not DOCKER_COMPOSE.exists():
        print("[WARN] docker-compose.yml 不存在")
        return False

    content = DOCKER_COMPOSE.read_text(encoding="utf-8")

    if "broker:" in content and "kafka" in content.lower():
        print("[WARN] docker-compose.yml 中仍包含 Kafka (broker) 服务")
        print("       请移除 Kafka 服务并添加 rabbitmq 和 meilisearch 服务")
        return False
    elif "rabbitmq:" in content and "meilisearch:" in content:
        print("[OK] docker-compose.yml 已包含 RabbitMQ 和 MeiliSearch 服务")
        return True
    else:
        print("[WARN] docker-compose.yml 中缺少 rabbitmq 或 meilisearch 服务定义")
        return False

def check_search_llm():
    """检查 search-llm 配置迁移"""
    if NEW_SEARCH_LLM.exists():
        print(f"[OK] search-llm 配置已迁移至: {NEW_SEARCH_LLM}")
        return True

    if OLD_SEARCH_LLM.exists():
        print(f"[WARN] search-llm 配置仍在旧位置: {OLD_SEARCH_LLM}")
        print("       v0.5 需要将 search-llm 配置迁移至 externalInfoService/config/")
        print("       且配置结构需要从 providers 改为 feature+profiles 格式")
    else:
        print("[INFO] search-llm 配置未找到")

    if NEW_SEARCH_EXAMPLE.exists():
        print(f"       请参考示例文件: {NEW_SEARCH_EXAMPLE}")

    return False

def main():
    print("v0.5 环境变量和配置检查")
    print("-" * 50)

    results = [
        check_env(),
        check_docker_compose(),
        check_search_llm(),
    ]

    print("-" * 50)
    if all(results):
        print("所有配置检查通过")
        return 0
    else:
        print("部分配置需要手动处理，请根据上方提示操作")
        print("特别注意: Kafka -> RabbitMQ 的迁移需要手动完成")
        return 0  # 非阻塞，仅提示

if __name__ == "__main__":
    sys.exit(main())

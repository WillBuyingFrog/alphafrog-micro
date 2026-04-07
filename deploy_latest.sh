#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

# 基础设施服务（不常重建）
INFRA_SERVICES=(
  redis
  rabbitmq
  nacos
  meilisearch
)

# Python沙箱服务（独立于Java服务）
PYTHON_SERVICES=(
  python-sandbox-service
)

# 业务服务（经常重建）
BUSINESS_SERVICES=(
  domestic-stock-service
  domestic-index-service
  domestic-fund-service
  domestic-fetch-service
  admin-service
  portfolio-service
  agent-service
  external-info-service
  python-sandbox-gateway-service
  frontend
)

# 所有服务
ALL_SERVICES=(
  "${BUSINESS_SERVICES[@]}"
)

usage() {
  cat <<'EOF'
Usage:
  ./deploy_latest.sh                  # rebuild all business services
  ./deploy_latest.sh serviceA serviceB
  ./deploy_latest.sh --services serviceA,serviceB
  ./deploy_latest.sh --with-infra     # rebuild with infrastructure services
  ./deploy_latest.sh --all            # rebuild all including python services
  ./deploy_latest.sh --deploy-only    # skip build, only recreate containers

Services:
  # Business Services
  domestic-stock-service
  domestic-index-service
  domestic-fund-service
  domestic-fetch-service
  admin-service
  portfolio-service
  agent-service
  external-info-service
  python-sandbox-gateway-service
  frontend

  # Infrastructure (use --with-infra to include)
  redis, rabbitmq, nacos, meilisearch

  # Python Services (use --all to include)
  python-sandbox-service
EOF
}

declare -A SERVICE_BUILD=(
  [domestic-stock-service]="domesticStockService/docker_build.sh"
  [domestic-index-service]="domesticIndexService/docker_build.sh"
  [domestic-fund-service]="domesticFundService/docker_build.sh"
  [domestic-fetch-service]="domesticFetchService/docker_build.sh"
  [admin-service]="adminService/docker_build.sh"
  [portfolio-service]="portfolioService/docker_build.sh"
  [agent-service]="agentService/docker_build.sh"
  [external-info-service]="externalInfoService/docker_build.sh"
  [python-sandbox-service]="pythonSandboxService/docker_build.sh"
  [python-sandbox-gateway-service]="pythonSandboxGatewayService/docker_build.sh"
  [frontend]="frontend/docker_build.sh"
)

declare -A SERVICE_MODULE=(
  [domestic-stock-service]="domesticStockService"
  [domestic-index-service]="domesticIndexService"
  [domestic-fund-service]="domesticFundService"
  [domestic-fetch-service]="domesticFetchService"
  [admin-service]="adminService"
  [portfolio-service]="portfolioService"
  [agent-service]="agentService"
  [external-info-service]="externalInfoService"
  [python-sandbox-gateway-service]="pythonSandboxGatewayService"
  [frontend]="frontend"
)

# 参数解析
RAW_SERVICES=()
WITH_INFRA=false
WITH_ALL=false
DEPLOY_ONLY=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --with-infra)
      WITH_INFRA=true
      shift
      ;;
    --all)
      WITH_ALL=true
      shift
      ;;
    --deploy-only)
      DEPLOY_ONLY=true
      shift
      ;;
    -s|--services)
      shift
      if [[ $# -eq 0 ]]; then
        echo "Missing value for --services" >&2
        usage
        exit 1
      fi
      RAW_SERVICES+=("$1")
      shift
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
    *)
      RAW_SERVICES+=("$1")
      shift
      ;;
  esac
done

# 解析服务列表
SERVICES=()
if [[ ${#RAW_SERVICES[@]} -gt 0 ]]; then
  for item in "${RAW_SERVICES[@]}"; do
    IFS=',' read -r -a parts <<< "$item"
    for part in "${parts[@]}"; do
      name="${part// /}"
      if [[ -n "$name" ]]; then
        SERVICES+=("$name")
      fi
    done
  done
fi

# 确定要构建的服务列表
SELECTED=()
if [[ ${#SERVICES[@]} -eq 0 ]]; then
  # 未指定服务，使用默认列表
  if [[ "$WITH_ALL" == true ]]; then
    SELECTED=("${PYTHON_SERVICES[@]}" "${BUSINESS_SERVICES[@]}")
  else
    SELECTED=("${BUSINESS_SERVICES[@]}")
  fi
else
  # 指定了具体服务
  declare -A seen=()
  for svc in "${SERVICES[@]}"; do
    if [[ -z "${SERVICE_BUILD[$svc]:-}" ]]; then
      echo "Unknown service: $svc" >&2
      usage
      exit 1
    fi
    seen["$svc"]=1
  done
  
  # 按 ALL_SERVICES 顺序输出
  for svc in "${ALL_SERVICES[@]}"; do
    if [[ -n "${seen[$svc]:-}" ]]; then
      SELECTED+=("$svc")
    fi
  done
  # 检查是否包含基础设施服务
  for svc in "${INFRA_SERVICES[@]}" "${PYTHON_SERVICES[@]}"; do
    if [[ -n "${seen[$svc]:-}" ]]; then
      SELECTED+=("$svc")
    fi
  done
fi

echo "=== Selected services: ${SELECTED[*]} ==="

# Maven 编译（--deploy-only 时跳过）
if [[ "$DEPLOY_ONLY" != true ]]; then
  if [[ ${#SERVICES[@]} -eq 0 ]]; then
    echo "=== Building all Java modules ==="
    mvn -DskipTests compile install
  else
    MODULES=()
    for svc in "${SELECTED[@]}"; do
      mod="${SERVICE_MODULE[$svc]:-}"
      if [[ -n "$mod" ]]; then
        MODULES+=("$mod")
      fi
    done
    if [[ ${#MODULES[@]} -gt 0 ]]; then
      echo "=== Building modules: ${MODULES[*]} ==="
      MODULE_LIST=$(IFS=','; echo "${MODULES[*]}")
      mvn -DskipTests -pl "$MODULE_LIST" -am compile install
    fi
  fi

  # Docker 构建镜像
  echo "=== Building Docker images ==="
  for svc in "${SELECTED[@]}"; do
    if [[ -n "${SERVICE_BUILD[$svc]:-}" ]]; then
      echo "Building: $svc"
      bash "${SERVICE_BUILD[$svc]}"
    fi
  done
else
  echo "=== Deploy-only mode: skipping build ==="
fi

# 检查 Docker Compose 命令
if command -v docker >/dev/null 2>&1; then
  if docker compose version >/dev/null 2>&1; then
    DOCKER_COMPOSE="docker compose"
  else
    DOCKER_COMPOSE="docker-compose"
  fi
else
  echo "docker not found in PATH" >&2
  exit 1
fi

# 步骤1: 启动基础设施服务
# 如果使用了 --with-infra 或指定了基础设施服务，则重建它们
if [[ "$WITH_INFRA" == true ]] || [[ "$WITH_ALL" == true ]]; then
  echo "=== Starting infrastructure services (with recreate) ==="
  $DOCKER_COMPOSE up -d --force-recreate "${INFRA_SERVICES[@]}"
else
  echo "=== Ensuring infrastructure services are running ==="
  $DOCKER_COMPOSE up -d --no-recreate "${INFRA_SERVICES[@]}" 2>/dev/null || true
fi

# 步骤2: 启动选定的业务服务（重建）
# 先过滤出需要重建的业务服务（排除基础设施）
BUSINESS_TO_RECREATE=()
for svc in "${SELECTED[@]}"; do
  # 检查是否是业务服务（有build脚本且在BUSINESS_SERVICES或PYTHON_SERVICES中）
  if [[ -n "${SERVICE_BUILD[$svc]:-}" ]]; then
    BUSINESS_TO_RECREATE+=("$svc")
  fi
done

if [[ ${#BUSINESS_TO_RECREATE[@]} -gt 0 ]]; then
  echo "=== Recreating business services: ${BUSINESS_TO_RECREATE[*]} ==="
  # 使用 --no-deps 避免连锁重建依赖服务
  # 因为步骤1已经确保了基础设施在运行
  $DOCKER_COMPOSE up -d --force-recreate --no-deps "${BUSINESS_TO_RECREATE[@]}"
fi

echo "=== Deployment completed ==="

# 显示状态
$DOCKER_COMPOSE ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}" 2>/dev/null || true

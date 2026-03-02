#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

usage() {
  cat <<'EOF'
Usage:
  ./deploy_latest.sh                  # rebuild all services
  ./deploy_latest.sh serviceA serviceB
  ./deploy_latest.sh --services serviceA,serviceB

Services:
  domestic-stock-service
  domestic-index-service
  domestic-fund-service
  domestic-fetch-service
  admin-service
  portfolio-service
  agent-service
  external-info-service
  frontend
EOF
}

ALL_SERVICES=(
  domestic-stock-service
  domestic-index-service
  domestic-fund-service
  domestic-fetch-service
  admin-service
  portfolio-service
  agent-service
  external-info-service
  python-sandbox-service
  python-sandbox-gateway-service
  frontend
)

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

RAW_SERVICES=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
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
    *)
      RAW_SERVICES+=("$1")
      shift
      ;;
  esac
done

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

SELECTED=()
if [[ ${#SERVICES[@]} -eq 0 ]]; then
  SELECTED=("${ALL_SERVICES[@]}")
else
  declare -A seen=()
  for svc in "${SERVICES[@]}"; do
    if [[ -z "${SERVICE_BUILD[$svc]:-}" ]]; then
      echo "Unknown service: $svc" >&2
      usage
      exit 1
    fi
    seen["$svc"]=1
  done
  for svc in "${ALL_SERVICES[@]}"; do
    if [[ -n "${seen[$svc]:-}" ]]; then
      SELECTED+=("$svc")
    fi
  done
fi

if [[ ${#SERVICES[@]} -eq 0 ]]; then
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
    MODULE_LIST=$(IFS=','; echo "${MODULES[*]}")
    mvn -DskipTests -pl "$MODULE_LIST" -am compile install
  fi
fi

for svc in "${SELECTED[@]}"; do
  bash "${SERVICE_BUILD[$svc]}"
done

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

$DOCKER_COMPOSE up -d --no-deps --force-recreate \
  "${SELECTED[@]}"

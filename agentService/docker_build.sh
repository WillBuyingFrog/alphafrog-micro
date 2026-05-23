#!/usr/bin/env bash
set -euo pipefail

USE_PROXY=${USE_PROXY:-1}

if [ "$USE_PROXY" = "1" ] || [ "$USE_PROXY" = "true" ]; then
  export https_proxy=http://127.0.0.1:7890 http_proxy=http://127.0.0.1:7890 all_proxy=socks5://127.0.0.1:7890
  PROXY_ARGS="--build-arg http_proxy=$http_proxy --build-arg https_proxy=$https_proxy"
else
  unset https_proxy http_proxy all_proxy
  PROXY_ARGS=""
fi

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVICE_DIR="$ROOT_DIR/agentService"
JAR="$SERVICE_DIR/target/agentService-1.0-SNAPSHOT.jar"
STALE_MAPPER_DIR="$SERVICE_DIR/target/classes/mapper"

if [[ -d "$STALE_MAPPER_DIR" ]]; then
  echo "ERROR: stale $STALE_MAPPER_DIR found (mapper XML moved to agentPlatformShared)." >&2
  echo "Run from repo root: mvn clean -DskipTests -pl agentService -am package" >&2
  exit 1
fi

if [[ -f "$JAR" ]] && jar tf "$JAR" | grep -q '^BOOT-INF/classes/mapper/'; then
  echo "ERROR: $JAR embeds stale BOOT-INF/classes/mapper/* (mapper XML moved to agentPlatformShared)." >&2
  echo "Run from repo root: mvn clean -DskipTests -pl agentService -am package" >&2
  exit 1
fi

if [[ ! -f "$JAR" ]]; then
  echo "=== agentService jar missing; running mvn clean package ==="
  (cd "$ROOT_DIR" && mvn clean -DskipTests -pl agentService -am package)
fi

docker build $PROXY_ARGS -t alphafrog-micro-agent-service:latest "$SERVICE_DIR"

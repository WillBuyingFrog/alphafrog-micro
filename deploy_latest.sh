#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

mvn -DskipTests compile install

bash build_all_images.sh

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
  domestic-stock-service \
  domestic-index-service \
  domestic-fund-service \
  domestic-fetch-service \
  portfolio-service \
  frontend

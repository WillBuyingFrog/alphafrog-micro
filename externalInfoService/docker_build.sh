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

# 使用 host 网络模式构建，使容器内 127.0.0.1 指向宿主机代理
docker build --network host $PROXY_ARGS -t alphafrog-micro-external-info-service:latest ./externalInfoService

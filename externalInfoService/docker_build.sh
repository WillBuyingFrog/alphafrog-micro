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

docker build $PROXY_ARGS -t alphafrog-micro-external-info-service:latest ./externalInfoService

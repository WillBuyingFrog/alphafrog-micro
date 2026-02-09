#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

USE_PROXY=${USE_PROXY:-1}

if [ "$USE_PROXY" = "1" ] || [ "$USE_PROXY" = "true" ]; then
  export https_proxy=http://127.0.0.1:7890 http_proxy=http://127.0.0.1:7890 all_proxy=socks5://127.0.0.1:7890
  PROXY_ARGS="--build-arg http_proxy=$http_proxy --build-arg https_proxy=$https_proxy"
else
  unset https_proxy http_proxy all_proxy
  PROXY_ARGS=""
fi

PIP_ARGS=""
if [ -n "${PIP_INDEX_URL:-}" ]; then
  PIP_ARGS="$PIP_ARGS --build-arg PIP_INDEX_URL=${PIP_INDEX_URL}"
fi

if [ -n "${PIP_EXTRA_INDEX_URL:-}" ]; then
  PIP_ARGS="$PIP_ARGS --build-arg PIP_EXTRA_INDEX_URL=${PIP_EXTRA_INDEX_URL}"
fi

# Build the runtime image for the sandbox (contains numpy, pandas, etc.)
docker build $PROXY_ARGS $PIP_ARGS -t alphafrog-sandbox-runtime:latest -f "$SCRIPT_DIR/Dockerfile.runtime" "$SCRIPT_DIR"

# Build the service image
docker build $PROXY_ARGS -t alphafrog-python-sandbox:latest "$SCRIPT_DIR"

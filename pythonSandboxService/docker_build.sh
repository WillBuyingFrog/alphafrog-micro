#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Build the runtime image for the sandbox (contains numpy, pandas, etc.)
docker build -t alphafrog-sandbox-runtime:latest -f "$SCRIPT_DIR/Dockerfile.runtime" "$SCRIPT_DIR"

# Build the service image
docker build -t alphafrog-python-sandbox:latest "$SCRIPT_DIR"

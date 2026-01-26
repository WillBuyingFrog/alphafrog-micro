#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

mvn -f "$ROOT_DIR/pom.xml" -pl pythonSandboxGatewayService -am clean package -DskipTests
docker build -t alphafrog-micro-python-sandbox-gateway-service:latest "$SCRIPT_DIR"

#!/bin/bash
# Build the runtime image for the sandbox (contains numpy, pandas, etc.)
docker build -t alphafrog-sandbox-runtime:latest -f Dockerfile.runtime .

# Build the service image
docker build -t alphafrog-python-sandbox:latest .
#!/bin/bash
mvn clean package -DskipTests
docker build -t alphafrog-micro-python-sandbox-gateway-service:latest .

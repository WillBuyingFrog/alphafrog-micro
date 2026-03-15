#!/bin/sh
# 获取非 172.20.x.x 的 IP（alphafrog-network）
DUBBO_IP=$(ip -4 addr show | grep "inet 172." | grep -v "172.20." | head -1 | awk '{print $2}' | cut -d/ -f1)

if [ -z "$DUBBO_IP" ]; then
    echo "[alphafrog] ERROR: Could not detect IP!"
    exit 1
fi

echo "[alphafrog] Detected DUBBO_IP: $DUBBO_IP"

# Triple 协议需要使用 TRI_DUBBO_IP_TO_BIND 环境变量
export TRI_DUBBO_IP_TO_BIND="$DUBBO_IP"
export TRI_DUBBO_IP_TO_REGISTRY="$DUBBO_IP"

exec java -jar /app/app.jar

#!/bin/sh
# 获取非 172.20.x.x 的 IP（alphafrog-network）
DUBBO_IP=$(ip -4 addr show | grep "inet 172." | grep -v "172.20." | head -1 | awk '{print $2}' | cut -d/ -f1)

if [ -z "$DUBBO_IP" ]; then
    echo "[alphafrog] ERROR: Could not detect IP!"
    exit 1
fi

echo "[alphafrog] Detected DUBBO_IP: $DUBBO_IP"
exec java -Ddubbo.protocols.tri.host="$DUBBO_IP" -jar /app/app.jar

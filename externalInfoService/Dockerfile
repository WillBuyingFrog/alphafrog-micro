FROM amazoncorretto:17
WORKDIR /app

# 安装网络工具（用于获取 IP 地址）
RUN yum install -y iproute && yum clean all

COPY target/externalInfoService-1.0-SNAPSHOT.jar app.jar

# Entrypoint 脚本：动态获取 alphafrog-network 的 IP 并启动
RUN echo '#!/bin/sh\n\
# 获取非 172.20.x.x 的 IP（alphafrog-network）\n\
DUBBO_IP=$(ip -4 addr show | grep "inet 172." | grep -v "172.20." | head -1 | awk "{print \$2}" | cut -d/ -f1)\n\
\n\
if [ -z "$DUBBO_IP" ]; then\n\
    echo "[alphafrog] ERROR: Could not detect IP!"\n\
    exit 1\n\
fi\n\
\n\
echo "[alphafrog] Detected DUBBO_IP: $DUBBO_IP"\n\
exec java -Ddubbo.protocols.tri.host="$DUBBO_IP" -jar /app/app.jar\n\
' > /entrypoint.sh && chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]

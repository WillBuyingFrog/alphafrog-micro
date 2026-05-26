FROM amazoncorretto:17
WORKDIR /app

# 安装网络工具（用于获取 IP 地址）
RUN yum install -y iproute && yum clean all

COPY target/externalInfoService-1.0-SNAPSHOT.jar app.jar
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]

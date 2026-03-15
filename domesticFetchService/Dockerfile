FROM openjdk:17.0.1-jdk-slim
COPY target/domesticFetchService-1.0-SNAPSHOT-exec.jar /app.jar
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app.jar"]
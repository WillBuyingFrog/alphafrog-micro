FROM openjdk:17.0.1-jdk-slim

# agentService 的版本为 1.0-SNAPSHOT，Jar 名为 agentService-1.0-SNAPSHOT.jar
COPY target/agentService-1.0-SNAPSHOT.jar /app.jar

ENTRYPOINT ["java", "-jar", "/app.jar"]

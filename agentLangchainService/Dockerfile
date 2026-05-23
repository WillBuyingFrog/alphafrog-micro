FROM openjdk:17.0.1-jdk-slim

# agentLangchainService 继承父 POM（1.0.0-SNAPSHOT），Jar 名为 agentLangchainService-1.0.0-SNAPSHOT.jar
COPY target/agentLangchainService-1.0.0-SNAPSHOT.jar /app.jar

ENTRYPOINT ["java", "-jar", "/app.jar"]

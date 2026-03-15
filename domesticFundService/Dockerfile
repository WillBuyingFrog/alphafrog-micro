FROM openjdk:17.0.1-jdk-slim
COPY target/domesticFundService-1.0-SNAPSHOT.jar /app.jar
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app.jar"]
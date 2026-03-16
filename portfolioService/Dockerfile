FROM openjdk:17.0.1-jdk-slim

# portfolioService 的版本继承父 POM（1.0.0-SNAPSHOT），Jar 名一般为 portfolioService-1.0.0-SNAPSHOT.jar
COPY target/portfolioService-1.0.0-SNAPSHOT.jar /app.jar

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app.jar"]


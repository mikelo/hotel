
# Fase di build
FROM maven:3.9.12-eclipse-temurin-8 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package
ENTRYPOINT ["java", "-jar", "/workspace/target/app.jar"]

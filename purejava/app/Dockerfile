
# Fase di build
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

# Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /opt/app
COPY --from=build /workspace/target/app.jar /opt/app/app.jar
ENTRYPOINT ["java", "-jar", "/opt/app/app.jar"]

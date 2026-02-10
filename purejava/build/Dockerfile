
# Fase di build
FROM maven:3.9.12-eclipse-temurin-8 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

# Runtime
FROM eclipse-temurin:8u412-b08-jre-focal
WORKDIR /opt/app
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar /opt/app/opentelemetry-javaagent.jar
ADD ca.pem /usr/local/share/ca-certificates/my-ca.crt
RUN update-ca-certificates
COPY --from=build /workspace/target/app.jar /opt/app/app.jar
ENTRYPOINT ["java", "-jar", "/opt/app/app.jar"]

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./gradlew build -x test

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
# OpenTelemetry Java agent — same rationale and setup as the other services
# (see shop/Dockerfile and docs/observability/observability.md).
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.30.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY --from=build /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "app.jar"]

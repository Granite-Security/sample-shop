FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./gradlew build -x test

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
# OpenTelemetry Java agent — instruments Reactor/Netty/Spring MVC/JDBC etc. via
# bytecode weaving at JVM startup, unlike the OTel Spring Boot starter (which
# has a broken WebFlux module against Spring Framework 7 — see
# docs/observability/observability.md). Reads OTEL_* env vars for config, same
# as the starter did, so no k8s manifest changes needed beyond this image.
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.30.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY --from=build /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "app.jar"]

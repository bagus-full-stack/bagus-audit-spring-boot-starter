# syntax=docker/dockerfile:1

# Shared source layer
FROM maven:3.9-eclipse-temurin-21 AS base
WORKDIR /src
COPY . .

# Run the whole test suite:  docker build --target test .
FROM base AS test
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp verify

# Build the sample application jar
FROM base AS build
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl sample -am package -DskipTests

# Runtime image (default target)
FROM eclipse-temurin:21-jre AS runtime
RUN useradd --system --uid 10001 --no-create-home audit
WORKDIR /app
COPY --from=build /src/sample/target/audit-sample-*.jar app.jar
USER audit
EXPOSE 8080
# The JRE image has no curl or wget; bash can open the socket itself.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

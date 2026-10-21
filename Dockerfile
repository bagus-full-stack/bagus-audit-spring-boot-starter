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
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# syntax=docker/dockerfile:1.7
#
# Multi-stage Dockerfile for ShopSphere.
#
# Stage 1 builds the fat jar with Maven. Stage 2 extracts Spring Boot's layered
# jars and copies each layer separately so a dependency change does not bust the
# application-classes layer (and vice versa). The runtime image is the Debian
# Temurin JRE — not Alpine, not distroless — because debuggability matters more
# than the last 50 MB.

# ---- Stage 1: build ----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Cache dependencies before copying sources so source-only changes hit a warm cache.
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package

# Extract layered jars so each layer lands on its own Docker layer in stage 2.
RUN mkdir -p /workspace/extracted \
 && java -Djarmode=layertools -jar target/*.jar extract --destination /workspace/extracted

# ---- Stage 2: runtime --------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# Non-root user — defense-in-depth.
RUN groupadd --system --gid 1001 shopsphere \
 && useradd  --system --uid 1001 --gid shopsphere shopsphere

WORKDIR /app

# Copy Spring Boot's layers in order from least → most likely to change.
COPY --from=build --chown=shopsphere:shopsphere /workspace/extracted/dependencies/         ./
COPY --from=build --chown=shopsphere:shopsphere /workspace/extracted/spring-boot-loader/   ./
COPY --from=build --chown=shopsphere:shopsphere /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=shopsphere:shopsphere /workspace/extracted/application/          ./

USER shopsphere

EXPOSE 8080

# Honour the container's memory cgroup limit; size the JVM heap from it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

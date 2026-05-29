---
status: accepted
date: 2026-05-29
cites: APoSD, XP, PragProg, PoEAA
---

# 0010 — Containerization: multi-stage Dockerfile + compose profiles to toggle app inclusion

ShopSphere now ships a multi-stage `Dockerfile` (Maven build stage, Temurin JRE runtime stage) and runs as an optional `app` service in the existing `docker-compose.yml`. The dev workflow — `docker compose up -d` for backing services, `mvn spring-boot:run` for the JVM — is unchanged. A second mode, `docker compose --profile full up -d --build`, brings up the **whole** stack including the JVM container; it is the production-parity path.

The image is built by hand rather than via buildpacks or Jib. Both alternatives are smaller and faster, but each hides exactly the layers (base image choice, user, JVM flags, COPY order) that turn into incident write-ups in production. **APoSD strategic + information hiding** says the right abstraction here is one we can read end-to-end: a 30-line Dockerfile with a clear seam between build and runtime, not a magic plugin. Spring Boot **layered jars** are extracted and copied as separate layers so a source-only change does not bust the dependency layer — **XP fast feedback** in the inner loop, where rebuilds are measured in seconds, not minutes. The runtime base is `eclipse-temurin:21-jre` (Debian, ~230 MB) and not Alpine or distroless: when something fails in a sandbox session, `docker exec sh` is the first move. The container runs as a non-root user — **PoEAA defense-in-depth**, applied at the OS boundary.

Compose layout is one file. The new `app` service is tagged `profiles: ["full"]`, so the default `docker compose up -d` continues to bring up Postgres + Kafka only — no breaking change for muscle memory. The new opt-in command surfaces deliberately as `--profile full`. **PragProg DRY** keeps a single config strategy: `application.yml` already used `${VAR:default}` substitution for Kafka, and Phase 10 extends the same pattern to the JDBC URL — no `application-docker.yml`, no parallel profile to maintain. Tags stay at `shopsphere:latest` until Phase 19, which is when CI starts pushing `:<git-sha>` to Docker Hub; doing it now would be **XP YAGNI**.

The image is verified end-to-end by a Testcontainers smoke test (`DockerImageSmokeIT`) that builds the Dockerfile, boots the resulting container against real Postgres + Kafka on a shared network, and asserts `/actuator/health` returns `UP`. The smoke test pairs with the existing in-JVM `@SpringBootTest` suite — those exercise the application code, this one exercises the Dockerfile itself: layered-jar assembly, non-root file permissions, JVM ergonomics, env-var substitution. **XP TDD** treats the manual ritual ("did the image actually run?") as a candidate test, and the test won.

Cost: every `mvn verify` now performs a Docker image build on first run (~3 minutes cold, ~5 seconds warm via the Testcontainers cache). Worth it — the smoke test catches a class of failure the in-JVM suite cannot see. Reversibility: high. The Dockerfile, `.dockerignore`, and the `app` service can be removed with zero impact on `mvn spring-boot:run` or the host-on-Postgres-and-Kafka workflow.

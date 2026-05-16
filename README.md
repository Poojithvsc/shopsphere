# ShopSphere

Learning-grade Spring Boot ecommerce project — built as a content engine: every shipped feature produces an article, every architectural decision is captured as an ADR.

## Stack

Java 21 LTS · Maven · Spring Boot 3.3.x · Postgres 16 · Kafka · Docker Compose · Testcontainers · JUnit 5 · Flyway · MapStruct · AWS SDK v2

## Guiding texts

Design decisions are anchored on:

- **Patterns of Enterprise Application Architecture** (Fowler)
- **A Philosophy of Software Design** (Ousterhout)
- **Domain-Driven Design** (Evans)
- **Extreme Programming Explained** (Beck)
- **The Pragmatic Programmer** (Hunt & Thomas)

Every ADR cites at least one source. See `docs/adr/`.

## Branching

- `main` — protected, release-ready commits only. Tagged for releases.
- `dev` — integration branch; default target for PRs.
- `feature/<slug>`, `fix/<slug>`, `chore/<slug>`, `docs/<slug>` — short-lived, branched off `dev`, merged via PR.

## Commit style

Conventional Commits: `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`, `build:`, `ci:`.

## Status

Bootstrap phase. Spring Boot scaffold lands after PRD → plan → first tracer-bullet slice.

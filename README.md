# ShopSphere

Learning-grade Spring Boot ecommerce backend, built as a **content engine**: every shipped feature produces an article, every architectural decision is captured as an ADR.

A modular monolith with four bounded contexts, enforced by Spring Modulith. The 9-phase MVP — walking skeleton through observability — is shipped.

## Stack

Java 21 LTS · Maven · Spring Boot 3.3.5 · Spring Modulith 1.2.5 · Postgres 16 · Apache Kafka · Flyway · springdoc-openapi 2.6.0 · JJWT · Micrometer + Actuator · Logback JSON (logstash encoder) · Testcontainers 1.20.3 · JUnit 5

Tests run against **real Postgres and real Kafka** via Testcontainers — no H2, no embedded Kafka, no mocks for domain modules.

> Roadmap: AWS S3 integration is part of the original vision but not yet wired in.

## Modules

Four bounded contexts under `com.shopsphere`, plus shared kernel, each owning its own Postgres schema and Flyway migration scope:

| Module | Owns |
|---|---|
| `catalog` | `Product` aggregate, stock as `StockReservation` (reserve / confirm / release) |
| `identity` | `User` + `Customer`, registration, JWT access tokens, DB-stored rotatable refresh tokens |
| `ordering` | `Cart` aggregate, `Order` aggregate + state machine, checkout, transactional outbox |
| `payment` | payment processing, event-driven order completion |
| `common` | shared kernel — `Money` value object |

Cross-schema joins are forbidden; `ApplicationModules.verify()` runs as a JUnit test and fails the build on any boundary leak. Each context publishes events only about its own concepts.

## API surface

REST under `/api/v1/...`. OpenAPI/Swagger UI at `/swagger-ui.html`. All endpoints except `/auth/register|login|refresh` require a Bearer access token.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/products` | list products |
| `GET` | `/api/v1/products/{id}` | one product |
| `POST` | `/api/v1/auth/register` | register user + customer |
| `POST` | `/api/v1/auth/login` | issue access + refresh tokens |
| `POST` | `/api/v1/auth/refresh` | rotate refresh token |
| `POST` | `/api/v1/auth/logout` | revoke refresh token |
| `GET` | `/api/v1/cart` | view cart |
| `POST` | `/api/v1/cart/items` | add item |
| `PATCH` | `/api/v1/cart/items/{productId}` | change quantity |
| `DELETE` | `/api/v1/cart/items/{productId}` | remove item |
| `POST` | `/api/v1/orders` | checkout — create order from cart |
| `GET` | `/api/v1/orders` | list the caller's orders |
| `GET` | `/api/v1/orders/{orderId}` | order detail |

Order state machine: `PENDING_PAYMENT → PAID`, plus `CANCELLED` from `PENDING_PAYMENT`.

## Build & test

```bash
mvn verify        # full build: unit + Testcontainers integration tests; this is the CI gate
```

**Docker must be running** — integration tests spin up Postgres and Kafka containers. `mvn verify` blocks merges to `dev` and `main` via GitHub Actions branch protection.

## Running locally

Two ways to run, same `docker-compose.yml`:

```bash
# Dev — fast inner loop, JVM on host
docker compose up -d                            # Postgres + Kafka only
mvn spring-boot:run                             # app on the host

# Prod-parity — JVM in a container too
docker compose --profile full up -d --build    # Postgres + Kafka + app
```

The `app` service is gated behind the `full` profile, so the default `docker compose up -d` continues to bring up only Postgres + Kafka — muscle memory is preserved. The prod-parity mode builds the image from the multi-stage `Dockerfile` and runs everything in containers; see [ADR-0010](docs/adr/0010-containerization-multi-stage-dockerfile-and-compose-profiles.md) for the design rationale.

Defaults come from `src/main/resources/application.yml`: Postgres at `localhost:5432` (db/user/password all `shopsphere`), Kafka at `localhost:9092`, and a dev-only `JWT_SECRET` fallback. All three are overridable per `${VAR:default}` substitution — `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `JWT_SECRET`.

Once up:

- Swagger UI — http://localhost:8080/swagger-ui.html
- Health — http://localhost:8080/actuator/health

Stop with `docker compose down` (keeps the database) or `docker compose down -v` (wipes it).

See **[docs/local-dev.md](docs/local-dev.md)** for the full QA runbook — an end-to-end curl flow (register → login → cart → checkout → order paid) and the payment test cards.

## Observability

Actuator endpoints exposed: `health`, `info`, `metrics`, `modulith`. Health includes a custom Kafka indicator. Build/git metadata surfaces at `/actuator/info`. Custom Micrometer meters: `orders_placed_total`, `checkout_latency_seconds`, `payments_total`, `reservations_total`. Logs are structured JSON with `orderId`/`customerId` MDC on order events.

## Guiding texts

Design decisions are anchored on — and every ADR cites at least one of:

- **Patterns of Enterprise Application Architecture** (Fowler)
- **A Philosophy of Software Design** (Ousterhout)
- **Domain-Driven Design** (Evans)
- **Extreme Programming Explained** (Beck)
- **The Pragmatic Programmer** (Hunt & Thomas)

See `docs/adr/` (ADR-0001 … ADR-0010) and the ubiquitous-language glossary in `docs/CONTEXT.md`.

## Branching & commits

Two branches, that's it:

- `main` — protected, release-ready; tagged for releases.
- `dev` — integration branch; **slice work commits directly here**.

No `feature/*`, `fix/*`, `chore/*`, or `docs/*` branches. Slices land as one or more conventional-commit-style commits on `dev` (`feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`, `build:`, `ci:`). At the end of an iteration, a single `dev → main` PR carries `Closes #N` for every issue the iteration shipped, and merges with a **merge commit** (not squash) to preserve per-slice history on `main`.

## Status

**MVP complete** — all 9 tracer-bullet phases shipped:

1. Walking skeleton — `GET /api/v1/products`
2. Spring Modulith — four-module boundaries
3. Identity — register, login, JWT-protected catalog
4. Refresh tokens — rotation, reuse detection, logout
5. Cart aggregate + `Money` value object
6. `StockReservation` aggregate
7. Checkout flow + transactional outbox
8. Event-driven order completion (payment loop)
9. Observability — JSON logs, Actuator, Micrometer

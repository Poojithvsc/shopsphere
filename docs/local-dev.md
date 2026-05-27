# Local development & QA runbook

How to run ShopSphere on your machine and verify it end-to-end.

## Prerequisites

- **Java 21** (Temurin). `java --version` should report `21.0.x`.
- **Maven** 3.9+.
- **Docker Desktop** running — needed both for the local stack *and* for `mvn verify` (Testcontainers).

## 1. Start the backing services

```bash
docker compose up -d
```

This starts:

| Service | Image | Host port | Credentials |
|---|---|---|---|
| Postgres | `postgres:16` | `5432` | `shopsphere` / `shopsphere`, db `shopsphere` |
| Kafka (KRaft) | `confluentinc/cp-kafka:7.6.1` | `9092` | — |

Check both are healthy:

```bash
docker compose ps
```

Wait until `STATUS` shows `healthy` for both before starting the app.

## 2. Run the app

```bash
mvn spring-boot:run
```

The app applies Flyway migrations (creating the four schemas and seeding three products) on startup and listens on **http://localhost:8080**. Flyway is idempotent, so restarts are safe; the Postgres data survives `docker compose down` and is wiped only by `docker compose down -v`.

Sanity checks:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP","components":{"db":{...UP},"kafka":{...UP,"nodeCount":1},...}}
```

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- Build/git info: http://localhost:8080/actuator/info
- Custom metrics: `/actuator/metrics/orders_placed_total`, `checkout_latency_seconds`, `payments_total`, `reservations_total`

A healthy `kafka` component (not just `db`) confirms the broker is actually reachable — that's the custom `KafkaHealthIndicator`, not a default.

## 3. End-to-end QA flow

Everything except `/api/v1/auth/**`, `/actuator/**`, and Swagger requires a Bearer access token. This walk-through registers a user, places an order, and watches the asynchronous Kafka event loop drive it to `PAID`.

```bash
BASE=http://localhost:8080
EMAIL="qa+$(date +%s)@shopsphere.test"

# Register (201) then login -> capture the access token
curl -s -X POST $BASE/api/v1/auth/register -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\"}"

TOKEN=$(curl -s -X POST $BASE/api/v1/auth/login -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\"}" \
  | grep -o '"accessToken":"[^"]*"' | sed 's/"accessToken":"//;s/"$//')

# List products (Flyway-seeded) and grab the first id
curl -s $BASE/api/v1/products -H "Authorization: Bearer $TOKEN"

# Add one unit of the seeded keyboard to the cart
curl -s -X POST $BASE/api/v1/cart/items \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"productId":"11111111-1111-1111-1111-111111111111","qty":1}'

# Checkout with the success card -> returns 202 + orderId, status PENDING_PAYMENT
curl -s -X POST $BASE/api/v1/orders \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"shippingAddress":"1 Test Street, Bengaluru","cardNumber":"4242424242424242"}'

# Poll the order; within a couple of seconds it flips to PAID
curl -s $BASE/api/v1/orders/<orderId> -H "Authorization: Bearer $TOKEN"
```

What happens between `PENDING_PAYMENT` and `PAID` is the whole point of the architecture: checkout writes the order plus an outbox record in one transaction, the outbox publishes `OrderPlaced` to `ordering.events`, the payment module consumes it and publishes `PaymentSucceeded` to `payment.events`, ordering moves the order to `PAID`, and catalog confirms the held stock — three independent consumers, no central coordinator, each idempotent against redelivery.

### Payment test cards

The simulator (`PaymentSimulator`) decides the outcome from the card number:

| Card number | Outcome | Order ends |
|---|---|---|
| `4242424242424242` | success | `PAID` (stock confirmed) |
| `4000000000009995` | insufficient funds | `CANCELLED` (stock released) |
| anything else | declined | `CANCELLED` (stock released) |

Run the flow again with `4000000000009995` to watch the order settle to `CANCELLED` and the reserved stock return to `availableQty`.

## 4. Run the test suite

```bash
mvn verify
```

Spins up its own throwaway Postgres + Kafka via Testcontainers (independent of the compose stack), runs unit + integration tests, and verifies the Spring Modulith boundaries. This is the same gate CI enforces on every PR.

## Troubleshooting

- **App can't reach Kafka / consumers stuck connecting** — the broker advertises `localhost:9092` only to host clients. If you run the app *inside* a container instead, point it at `kafka:29092` via `KAFKA_BOOTSTRAP_SERVERS`.
- **`ddl-auto: validate` fails on startup** — the schema drifted from the entities. Reset with `docker compose down -v && docker compose up -d` to re-run Flyway from clean.
- **Port already in use (5432/9092/8080)** — stop the conflicting service or remap the host port in `docker-compose.yml`.
- **`java --version` isn't 21** — a non-Temurin JDK is shadowing `JAVA_HOME`/`PATH`; point them at the Temurin 21 install before running Maven.

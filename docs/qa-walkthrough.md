# ShopSphere — End-to-End QA Walkthrough

A single-pass tour from a cold machine to having touched **every feature** in the MVP. Follow it once start-to-end and you will have:

- Exercised auth: register, login, refresh, logout, refresh-reuse detection
- Browsed the catalog
- Used the cart: add, view, change quantity, remove
- Run a checkout through all three terminal outcomes (PAID, declined, insufficient funds)
- Watched the asynchronous Kafka event loop drive an order to its terminal state
- Verified observability (health, metrics) and the Spring Modulith boundary check
- Run the full test suite

> Companion to [`local-dev.md`](./local-dev.md). That file is the *quick-reference runbook*; this one is the *guided tour*. Commands assume **Windows PowerShell** since that's the project's primary dev environment; bash equivalents are in `local-dev.md`.

---

## 0. Prerequisites (one-time)

Verify all four before you start. If any are missing, install before continuing.

| Tool | How to check | Expected |
|---|---|---|
| **Java 21 (Temurin)** | `java --version` | `openjdk 21.0.x` or `Temurin-21…` |
| **Maven 3.9+** | `mvn -v` | `Apache Maven 3.9.x` (or newer) |
| **Docker Desktop** | `docker --version` and the whale icon in the system tray | `Docker version 24.x` or newer, **and** the icon shows "Docker Desktop is running" |
| **Git** | `git --version` | any recent version |

Common pitfall on this machine: if `java --version` shows OpenJDK **24** instead of 21, IntelliJ's bundled JDK is shadowing your user-level `JAVA_HOME`. Fix `JAVA_HOME` + `PATH` to point at the Temurin 21 install before running anything below, or Maven will compile against the wrong target.

You also need a terminal that can hit `http://localhost:8080`. PowerShell, Windows Terminal, or Git Bash all work.

---

## 1. Start Docker Desktop

1. Launch **Docker Desktop** from the Start menu.
2. Wait for the whale icon in the system tray to stop animating — that's when the Docker engine is actually ready.
3. Sanity check from your terminal:

   ```powershell
   docker info
   ```

   You should see a normal report (server version, containers count). If you see `error during connect: ... pipe/docker_engine: The system cannot find the file specified`, Docker Desktop hasn't finished starting — wait another 30 seconds and retry.

---

## 2. Open the project

```powershell
cd D:\shopsphere-project\code
```

That's the **code** repo (Java). The vault repo (`D:\shopsphere-project\vault`) holds plans/ADRs/diagrams and is not needed for QA.

A quick orientation pass (optional but recommended once):

```powershell
type README.md                          # current shipped status, endpoint map
type docs\local-dev.md                  # the runbook
type docs\CONTEXT.md                    # ubiquitous-language glossary
dir docs\adr                            # ADR-0001..0009
```

---

## 3. Start the backing services

From inside `D:\shopsphere-project\code`:

```powershell
docker compose up -d
```

This launches two containers in the project's docker network:

| Container | Image | Host port | Purpose |
|---|---|---|---|
| `shopsphere-postgres` | `postgres:16` | `5432` | One DB (`shopsphere`), four schemas (catalog, identity, ordering, payment) |
| `shopsphere-kafka` | `confluentinc/cp-kafka:7.6.1` | `9092` | KRaft single-node broker, topics auto-create |

Wait for **both** to report `healthy` before continuing:

```powershell
docker compose ps
```

The `STATUS` column should show `Up X (healthy)` for both. If one is still `health: starting`, give it another 10–15 seconds. Postgres goes healthy in a few seconds; Kafka takes longer because the healthcheck calls `kafka-broker-api-versions`.

> **What's *not* in compose:** the Spring Boot app itself. It runs on the host JVM via `mvn spring-boot:run` — that's deliberate (faster iteration, easier debugging). See `diagrams/docker-compose-topology.excalidraw` in the vault for the visual.

---

## 4. Start the app

In the same terminal (still in `D:\shopsphere-project\code`):

```powershell
mvn spring-boot:run
```

Watch the log for, in order:

1. `Started Flyway 10.x ...` and migrations `V1` through `V10` applying
2. `Tomcat started on port 8080`
3. `Started ShopSphereApplication in N.NN seconds`

On startup Flyway creates the 4 schemas, all tables, and seeds **3 products** (keyboard, mouse, monitor — IDs `111...`, `222...`, `333...`). Restarts are idempotent.

If startup fails on `SchemaManagementException: Schema-validation`, you've got migration drift — see Troubleshooting at the end.

**Leave this terminal open.** Open a *second* terminal for the rest of the walkthrough.

---

## 5. Verify the app is alive

In the second terminal:

```powershell
curl http://localhost:8080/actuator/health
```

Expected JSON contains `"status":"UP"` plus `"db":{...,"status":"UP"}` and **`"kafka":{...,"status":"UP","details":{"nodeCount":1}}`**. The Kafka entry is the project's custom `KafkaHealthIndicator` — if Kafka were down or unreachable, this would show `DOWN` even though the app itself starts.

Other useful endpoints:

| URL | What it is |
|---|---|
| `http://localhost:8080/swagger-ui.html` | **Interactive API explorer — recommended for first-time exploration** |
| `http://localhost:8080/v3/api-docs` | Raw OpenAPI 3 JSON |
| `http://localhost:8080/actuator/info` | Build/git commit info |
| `http://localhost:8080/actuator/metrics` | List of available metrics |
| `http://localhost:8080/` | **401 by design** — there is no homepage; this is a REST API |

> Open `http://localhost:8080/swagger-ui.html` in a browser now. You'll use it alongside the commands below — Swagger is the easiest way to *see* every endpoint, but the curl commands below capture state across calls (tokens, IDs) which is awkward in Swagger.

---

## 6. Auth: register a fresh user

PowerShell:

```powershell
$BASE = "http://localhost:8080"
$EMAIL = "qa+$(Get-Date -Format yyyyMMddHHmmss)@shopsphere.test"
$PASSWORD = "password1234"

$reg = curl.exe -s -X POST "$BASE/api/v1/auth/register" `
  -H "Content-Type: application/json" `
  -d "{`"email`":`"$EMAIL`",`"password`":`"$PASSWORD`"}"
$reg
```

Expected response (201): `{"userId":"...","customerId":"..."}`

> **What to look for:** the response carries *two* IDs. That's not redundancy — `User` is the identity row (email + password hash) and `Customer` is the domain actor (owns the cart, places orders). They are materialized together in **one transaction** at registration. See `CONTEXT.md` → "User" and "Customer".

### Negative path

Try to register again with the same email:

```powershell
curl.exe -s -o NUL -w "%{http_code}`n" -X POST "$BASE/api/v1/auth/register" `
  -H "Content-Type: application/json" `
  -d "{`"email`":`"$EMAIL`",`"password`":`"$PASSWORD`"}"
```

Expected: **`409`** (DuplicateEmailException → 409 Conflict).

---

## 7. Auth: log in and capture tokens

```powershell
$login = curl.exe -s -X POST "$BASE/api/v1/auth/login" `
  -H "Content-Type: application/json" `
  -d "{`"email`":`"$EMAIL`",`"password`":`"$PASSWORD`"}" | ConvertFrom-Json

$ACCESS = $login.accessToken
$REFRESH = $login.refreshToken
"access token length: $($ACCESS.Length)"
"refresh token length: $($REFRESH.Length)"
```

Expected: access token ~305 chars (a real JWT), refresh token a long random string.

> **What to look for:** the access token is a JWT (decode it at `jwt.io` if curious — the payload carries `sub=userId`, `customerId`, `exp`). The refresh token is an **opaque token** whose *hash* is stored in Postgres (`identity.refresh_tokens`); the raw value lives only in this response. That asymmetry is intentional — JWT = stateless and verifiable; refresh = revocable. ADR-0006 explains the trade-off.

### Negative path

Wrong password:

```powershell
curl.exe -s -o NUL -w "%{http_code}`n" -X POST "$BASE/api/v1/auth/login" `
  -H "Content-Type: application/json" `
  -d "{`"email`":`"$EMAIL`",`"password`":`"WRONG`"}"
```

Expected: **`401`**.

---

## 8. Browse the catalog

Every endpoint below requires `Authorization: Bearer <accessToken>`.

```powershell
$AUTH = "Authorization: Bearer $ACCESS"

curl.exe -s "$BASE/api/v1/products" -H $AUTH | ConvertFrom-Json | Format-Table id, name, availableQty
```

Expected: 3 rows — Aurora Keyboard (12), Nimbus Mouse (8), Vertex Monitor (4).

Get one product:

```powershell
curl.exe -s "$BASE/api/v1/products/11111111-1111-1111-1111-111111111111" -H $AUTH
```

Without the Bearer token:

```powershell
curl.exe -s -o NUL -w "%{http_code}`n" "$BASE/api/v1/products"
```

Expected: **`401`**.

---

## 9. Cart: full CRUD on line items

The cart auto-creates on first access — there is no "create cart" endpoint.

### 9a. View (creates if not exists)

```powershell
curl.exe -s "$BASE/api/v1/cart" -H $AUTH | ConvertFrom-Json
```

Expected: empty `items: []`, `grandTotal: 0.00 INR`.

### 9b. Add an item

```powershell
curl.exe -s -X POST "$BASE/api/v1/cart/items" `
  -H $AUTH -H "Content-Type: application/json" `
  -d '{"productId":"11111111-1111-1111-1111-111111111111","qty":1}' | ConvertFrom-Json
```

Expected: 1 line item, `grandTotal: 8499.00 INR`.

### 9c. Add a second product

```powershell
curl.exe -s -X POST "$BASE/api/v1/cart/items" `
  -H $AUTH -H "Content-Type: application/json" `
  -d '{"productId":"22222222-2222-2222-2222-222222222222","qty":2}' | ConvertFrom-Json
```

Expected: 2 lines, `grandTotal: 8499 + (5999 × 2) = 20497.00 INR`.

### 9d. Patch the quantity

Change the keyboard from qty 1 to qty 3:

```powershell
curl.exe -s -X PATCH "$BASE/api/v1/cart/items/11111111-1111-1111-1111-111111111111" `
  -H $AUTH -H "Content-Type: application/json" `
  -d '{"qty":3}' | ConvertFrom-Json
```

Expected: keyboard line shows `qty: 3, lineTotal: 25497.00`. Grand total = `25497 + 11998 = 37495.00 INR`.

### 9e. Set qty to 0 removes the line

```powershell
curl.exe -s -X PATCH "$BASE/api/v1/cart/items/22222222-2222-2222-2222-222222222222" `
  -H $AUTH -H "Content-Type: application/json" `
  -d '{"qty":0}' | ConvertFrom-Json
```

Expected: only the keyboard remains.

### 9f. Explicit delete

Re-add the mouse, then delete it:

```powershell
curl.exe -s -X POST "$BASE/api/v1/cart/items" `
  -H $AUTH -H "Content-Type: application/json" `
  -d '{"productId":"22222222-2222-2222-2222-222222222222","qty":1}' | Out-Null

curl.exe -s -X DELETE "$BASE/api/v1/cart/items/22222222-2222-2222-2222-222222222222" -H $AUTH | ConvertFrom-Json
```

Expected: only the keyboard with `qty: 3`.

### Negative paths

```powershell
# Unknown product → 404
curl.exe -s -o NUL -w "%{http_code}`n" -X POST "$BASE/api/v1/cart/items" `
  -H $AUTH -H "Content-Type: application/json" `
  -d '{"productId":"99999999-9999-9999-9999-999999999999","qty":1}'

# Invalid quantity (0 on POST) → 400
curl.exe -s -o NUL -w "%{http_code}`n" -X POST "$BASE/api/v1/cart/items" `
  -H $AUTH -H "Content-Type: application/json" `
  -d '{"productId":"11111111-1111-1111-1111-111111111111","qty":0}'
```

---

## 10. Checkout — the happy path (PAID)

The keyboard cart from §9 should still be there (qty 3 = ₹25 497). Check out with the **success card**:

```powershell
$order = curl.exe -s -X POST "$BASE/api/v1/orders" `
  -H $AUTH -H "Content-Type: application/json" `
  -d '{"shippingAddress":"1 Test Street, Bengaluru","cardNumber":"4242424242424242"}' | ConvertFrom-Json
$order
$ORDER_ID = $order.orderId
```

Expected: HTTP 202 + `{"orderId":"...","status":"PENDING_PAYMENT"}`.

> **What just happened (in 50 ms):**
> 1. A transaction snapshotted the cart → an `Order` in `PENDING_PAYMENT` state.
> 2. The same transaction inserted a `StockReservation` row with `status=HELD` in catalog.
> 3. The same transaction wrote an `OrderPlaced` event to the **outbox** table.
> 4. The HTTP response returned. The customer's part is done.
>
> The next ~1 second is fully asynchronous — see below.

### Watch the event loop run

Poll the order until it changes:

```powershell
for ($i=1; $i -le 10; $i++) {
  $o = curl.exe -s "$BASE/api/v1/orders/$ORDER_ID" -H $AUTH | ConvertFrom-Json
  "$($i): $($o.status)"
  if ($o.status -ne "PENDING_PAYMENT") { break }
  Start-Sleep -Milliseconds 300
}
```

Expected: 1–3 polls in `PENDING_PAYMENT`, then **`PAID`**.

> **What ran in the background between your HTTP response and `PAID`:**
> 1. Outbox publisher picked up the `OrderPlaced` row → published to topic `ordering.events`.
> 2. `payment` module consumed `OrderPlaced` → `PaymentSimulator.process()` matched `4242…` → success → published `PaymentSucceeded` to `payment.events`.
> 3. `ordering` module consumed `PaymentSucceeded` → moved order to `PAID` → published `OrderPaid`.
> 4. `catalog` module consumed `OrderPaid` → reservation `HELD → CONFIRMED`, qty leaves the system permanently.
>
> Every consumer wrote to its own `processed_events(consumer_id, event_id)` table in the same transaction as its side effect — that's the de-dupe layer that makes Kafka's at-least-once delivery safe. See `diagrams/checkout-sequence.excalidraw` for a sequence view.

### Verify stock decremented

```powershell
curl.exe -s "$BASE/api/v1/products/11111111-1111-1111-1111-111111111111" -H $AUTH | ConvertFrom-Json
```

Expected: `availableQty: 9` (was 12, you ordered 3).

The cart should also be empty now:

```powershell
curl.exe -s "$BASE/api/v1/cart" -H $AUTH | ConvertFrom-Json
```

Expected: `items: []`. (ADR-0008: cart is *not* restored on failed payment; cleared on success.)

---

## 11. Checkout — declined card (CANCELLED, stock RELEASED)

Add the mouse to the cart again, then check out with the **declined card**:

```powershell
curl.exe -s -X POST "$BASE/api/v1/cart/items" `
  -H $AUTH -H "Content-Type: application/json" `
  -d '{"productId":"22222222-2222-2222-2222-222222222222","qty":1}' | Out-Null

$declined = curl.exe -s -X POST "$BASE/api/v1/orders" `
  -H $AUTH -H "Content-Type: application/json" `
  -d '{"shippingAddress":"1 Test Street","cardNumber":"4000000000000002"}' | ConvertFrom-Json
$DECLINED_ID = $declined.orderId

for ($i=1; $i -le 10; $i++) {
  $o = curl.exe -s "$BASE/api/v1/orders/$DECLINED_ID" -H $AUTH | ConvertFrom-Json
  "$($i): $($o.status)"
  if ($o.status -ne "PENDING_PAYMENT") { break }
  Start-Sleep -Milliseconds 300
}
```

Expected: order ends in **`CANCELLED`**. Mouse stock should return to **8**:

```powershell
curl.exe -s "$BASE/api/v1/products/22222222-2222-2222-2222-222222222222" -H $AUTH | ConvertFrom-Json
```

---

## 12. Checkout — insufficient funds (CANCELLED, distinct reason)

Add the mouse again, check out with the **insufficient-funds card**:

```powershell
curl.exe -s -X POST "$BASE/api/v1/cart/items" `
  -H $AUTH -H "Content-Type: application/json" `
  -d '{"productId":"22222222-2222-2222-2222-222222222222","qty":1}' | Out-Null

$insf = curl.exe -s -X POST "$BASE/api/v1/orders" `
  -H $AUTH -H "Content-Type: application/json" `
  -d '{"shippingAddress":"1 Test Street","cardNumber":"4000000000009995"}' | ConvertFrom-Json
$INSF_ID = $insf.orderId

for ($i=1; $i -le 10; $i++) {
  $o = curl.exe -s "$BASE/api/v1/orders/$INSF_ID" -H $AUTH | ConvertFrom-Json
  "$($i): $($o.status)"
  if ($o.status -ne "PENDING_PAYMENT") { break }
  Start-Sleep -Milliseconds 300
}
```

Expected: `CANCELLED`. Both `4000…0002` and `4000…9995` cancel, but the underlying `PaymentFailed` event carries different `reason` values (`DECLINED` vs `INSUFFICIENT_FUNDS`) — visible in the app logs as the consumer processes the event.

---

## 13. List & inspect orders

```powershell
curl.exe -s "$BASE/api/v1/orders" -H $AUTH | ConvertFrom-Json | Format-Table id, status, total
```

Expected: 3 rows — one PAID, two CANCELLED.

```powershell
curl.exe -s "$BASE/api/v1/orders/$ORDER_ID" -H $AUTH | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

Expected: full order with the **snapshotted** line items (name + unit price + qty preserved at checkout time — they will not change even if the product's price is later updated). That's the "Order is immutable" property from CONTEXT.

---

## 14. Refresh tokens & reuse detection

### Normal refresh

```powershell
$rotated = curl.exe -s -X POST "$BASE/api/v1/auth/refresh" `
  -H "Content-Type: application/json" `
  -d "{`"refreshToken`":`"$REFRESH`"}" | ConvertFrom-Json
$NEW_ACCESS = $rotated.accessToken
$NEW_REFRESH = $rotated.refreshToken
"new access token issued: length $($NEW_ACCESS.Length)"
```

Expected: a fresh pair. The **old** refresh token is now marked rotated and may never be used again.

### Reuse-detection trip

Try to use the **old** refresh token one more time:

```powershell
curl.exe -s -o NUL -w "%{http_code}`n" -X POST "$BASE/api/v1/auth/refresh" `
  -H "Content-Type: application/json" `
  -d "{`"refreshToken`":`"$REFRESH`"}"
```

Expected: **`401`**. The rotated refresh is dead, AND (per ADR-0006) the system treats reuse as evidence of theft — *every* refresh token in the user's family is invalidated. Try the **new** refresh too:

```powershell
curl.exe -s -o NUL -w "%{http_code}`n" -X POST "$BASE/api/v1/auth/refresh" `
  -H "Content-Type: application/json" `
  -d "{`"refreshToken`":`"$NEW_REFRESH`"}"
```

Expected: **`401`** as well — the family was wiped. This is the textbook refresh-token-reuse-detection pattern; very few demos actually implement it.

### Recover by logging in again

```powershell
$login2 = curl.exe -s -X POST "$BASE/api/v1/auth/login" `
  -H "Content-Type: application/json" `
  -d "{`"email`":`"$EMAIL`",`"password`":`"$PASSWORD`"}" | ConvertFrom-Json
$ACCESS = $login2.accessToken
$REFRESH = $login2.refreshToken
$AUTH = "Authorization: Bearer $ACCESS"
"logged back in"
```

### Explicit logout

```powershell
curl.exe -s -o NUL -w "%{http_code}`n" -X POST "$BASE/api/v1/auth/logout" `
  -H "Content-Type: application/json" `
  -d "{`"refreshToken`":`"$REFRESH`"}"
```

Expected: **`204`**. The refresh is now revoked; the access token still works until it naturally expires (it's stateless JWT).

---

## 15. Observability

```powershell
curl.exe -s "$BASE/actuator/metrics/orders_placed_total"        | ConvertFrom-Json
curl.exe -s "$BASE/actuator/metrics/checkout_latency_seconds"   | ConvertFrom-Json
curl.exe -s "$BASE/actuator/metrics/payments_total"             | ConvertFrom-Json
curl.exe -s "$BASE/actuator/metrics/reservations_total"         | ConvertFrom-Json
```

Expected:
- `orders_placed_total` → COUNT 3.0 (three checkouts)
- `payments_total` carries dimensions for `outcome=SUCCEEDED|FAILED` and `reason=DECLINED|INSUFFICIENT_FUNDS`
- `reservations_total` carries dimensions for `HELD`, `CONFIRMED`, `RELEASED`
- `checkout_latency_seconds` is a histogram with `count`, `total_time`, percentile buckets

Other useful actuator endpoints:

```powershell
curl.exe -s "$BASE/actuator/info"                              # build/commit
curl.exe -s "$BASE/actuator/health" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

---

## 16. Spring Modulith boundary check & full test suite

In **the app terminal**, press `Ctrl+C` to stop `mvn spring-boot:run` (Postgres + Kafka stay up — that's fine, the tests use their own throwaway containers).

Then run the full verification gate:

```powershell
mvn verify
```

This:

1. Runs unit + integration tests (Testcontainers spins up its own Postgres + Kafka, separate from your compose stack).
2. Runs `ApplicationModulesTest` — the Spring Modulith structure test. **This fails the build if any module imports another module's internals** (cross-context coupling). It's the automated enforcement of the bounded-context lines in `CONTEXT.md`.
3. Generates the Modulith docs under `target/spring-modulith-docs/` (PlantUML of the module graph).

Expected: `BUILD SUCCESS`. If any test or the modulith check fails, that's a regression — don't merge.

After it finishes, restart the app for any further manual work:

```powershell
mvn spring-boot:run
```

---

## 17. Teardown

Stop the app (`Ctrl+C` in the app terminal), then:

```powershell
docker compose down            # stops Postgres + Kafka, keeps the DB volume
```

If you want a **fully clean slate** next time (re-runs Flyway from zero, you'd register a new user, products reset to original quantities):

```powershell
docker compose down -v         # also wipes the shopsphere-pgdata volume
```

You can also leave Docker Desktop running between sessions — it just sits in the tray.

---

## Quick reference

### Test cards (`PaymentSimulator`)

| Card | Outcome | Terminal state |
|---|---|---|
| `4242 4242 4242 4242` | success | `PAID` (stock CONFIRMED) |
| `4000 0000 0000 0002` | declined | `CANCELLED` (stock RELEASED) |
| `4000 0000 0000 9995` | insufficient funds | `CANCELLED` (stock RELEASED) |
| anything else | declined | `CANCELLED` (stock RELEASED) |

### Seeded products

| UUID | Name | Price | Qty |
|---|---|---|---|
| `11111111-…` | Aurora Mechanical Keyboard | ₹8 499 | 12 |
| `22222222-…` | Nimbus Wireless Mouse | ₹5 999 | 8 |
| `33333333-…` | Vertex 27" 4K Monitor | ₹42 999 | 4 |

### Endpoint cheat sheet

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | — | Create User + Customer |
| `POST` | `/api/v1/auth/login` | — | Issue access + refresh |
| `POST` | `/api/v1/auth/refresh` | — | Rotate refresh, mint new access |
| `POST` | `/api/v1/auth/logout` | — | Revoke refresh |
| `GET`  | `/api/v1/products` | Bearer | List catalog |
| `GET`  | `/api/v1/products/{id}` | Bearer | One product |
| `GET`  | `/api/v1/cart` | Bearer | View (auto-creates) |
| `POST` | `/api/v1/cart/items` | Bearer | Add line |
| `PATCH`| `/api/v1/cart/items/{productId}` | Bearer | Change qty (0 = remove) |
| `DELETE`| `/api/v1/cart/items/{productId}` | Bearer | Remove line |
| `POST` | `/api/v1/orders` | Bearer | Checkout |
| `GET`  | `/api/v1/orders` | Bearer | My orders |
| `GET`  | `/api/v1/orders/{id}` | Bearer | One order |

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `docker info` errors with "pipe not found" | Docker Desktop not fully started | Wait 30s, retry |
| `docker compose ps` shows kafka `unhealthy` | Broker still starting | Wait 15s; if persistent, `docker compose logs kafka` |
| App startup fails with `SchemaManagementException` | Flyway/entity drift (`ddl-auto: validate`) | `docker compose down -v && docker compose up -d` and restart app |
| App startup fails with `Address already in use: 8080` | Another process on port 8080 | Find it (`netstat -ano | findstr :8080`) and stop it |
| App startup fails with `Connection refused: 5432`/`9092` | Container not healthy yet | Wait for `docker compose ps` healthy |
| `/api/v1/products` returns `401` | Forgot the Bearer header | Re-issue: `$AUTH = "Authorization: Bearer $ACCESS"` |
| `mvn` uses JDK 24 not 21 | IntelliJ JDK shadowing `JAVA_HOME` | Set user `JAVA_HOME` + `PATH` to Temurin 21 |
| Order stuck in `PENDING_PAYMENT` after 5+ seconds | Kafka unhealthy, or app stopped before consumers caught up | Check `/actuator/health`; check app logs for Kafka errors |
| `mvn verify` fails on `ApplicationModulesTest` | Someone introduced a forbidden cross-module import | Read the failure — it names the offending class & import |

---

## What to read next

- `docs/CONTEXT.md` — the ubiquitous language. Names here match Java classes and DB tables.
- `docs/adr/` — every architectural decision and why. Start with 0001 (modulith over microservices), 0003 (Spring Modulith), 0006 (JWT + refresh), 0008 (order states).
- `vault/projects/shopsphere/diagrams/checkout-sequence.excalidraw` — the §10 flow as a sequence diagram (open with the Excalidraw VS Code extension or Obsidian).
- `vault/projects/shopsphere/diagrams/transactional-outbox.excalidraw` — *why* the event loop is reliable.
- `vault/projects/shopsphere/diagrams/level-4-components.excalidraw` — the 4 bounded contexts + Kafka topics.

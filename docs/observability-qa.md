# ShopSphere — Observability QA (Phases 18a + 18b)

The end-to-end checks that `mvn verify` **cannot** make: that the Grafana dashboard panels light up with real data (18a), that a Loki search for an `orderId` returns every module's log line for that order (18b), and that Kafka UI shows the topics/partitions/offsets (18b).

> Unlike [`qa-walkthrough.md`](./qa-walkthrough.md) — which runs the app on the host with backing services only — this checklist runs the **`full` compose profile**: the app is containerised so promtail can tail its logs and Prometheus can scrape it. Commands assume **Windows PowerShell**.

| What | URL | Credentials |
|---|---|---|
| App | `http://localhost:8080` | Bearer token (below) |
| Grafana | `http://localhost:3000` | `admin` / `admin` (first-login change prompt) |
| Prometheus | `http://localhost:9090` | — |
| Loki (via Grafana Explore) | `http://localhost:3100` | — |
| Kafka UI | `http://localhost:8081` | — |

---

## 1. Bring up the full stack

From `D:\shopsphere-project\code`, with Docker Desktop running:

```powershell
docker compose --profile full up -d --build
```

This builds the app image and starts: `postgres`, `kafka`, `localstack`, `app`, `prometheus`, `grafana`, `loki`, `promtail`, `kafka-ui`.

Wait for the app to be healthy (it's the long pole — it waits on Postgres + Kafka + LocalStack):

```powershell
docker compose ps
curl.exe -s http://localhost:8080/actuator/health | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

- [ ] `docker compose ps` shows all nine containers `Up`; `shopsphere-app` is `healthy`.
- [ ] `/actuator/health` returns `"status":"UP"` with `db` and `kafka` both `UP`.

> If the build fails on a buildx snapshot error (`parent snapshot … not found`), the local build cache is corrupt: `docker builder prune -af` then re-run the `up` command. (Seen during Phase 18b verify.)

---

## 2. Generate an order (gives you an `orderId` to trace)

Run the auth + checkout portion of the main walkthrough against the containerised app. Minimal happy-path version:

```powershell
$BASE = "http://localhost:8080"
$EMAIL = "qa+$(Get-Date -Format yyyyMMddHHmmss)@shopsphere.test"
$PASSWORD = "password1234"

# register + login
curl.exe -s -X POST "$BASE/api/v1/auth/register" -H "Content-Type: application/json" `
  -d "{`"email`":`"$EMAIL`",`"password`":`"$PASSWORD`"}" | Out-Null
$login = curl.exe -s -X POST "$BASE/api/v1/auth/login" -H "Content-Type: application/json" `
  -d "{`"email`":`"$EMAIL`",`"password`":`"$PASSWORD`"}" | ConvertFrom-Json
$AUTH = "Authorization: Bearer $($login.accessToken)"

# add the keyboard, check out with the success card
curl.exe -s -X POST "$BASE/api/v1/cart/items" -H $AUTH -H "Content-Type: application/json" `
  -d '{"productId":"11111111-1111-1111-1111-111111111111","qty":1}' | Out-Null
$order = curl.exe -s -X POST "$BASE/api/v1/orders" -H $AUTH -H "Content-Type: application/json" `
  -d '{"shippingAddress":"1 Test Street, Bengaluru","cardNumber":"4242424242424242"}' | ConvertFrom-Json
$ORDER_ID = $order.orderId
"orderId = $ORDER_ID"

# poll to PAID
for ($i=1; $i -le 10; $i++) {
  $o = curl.exe -s "$BASE/api/v1/orders/$ORDER_ID" -H $AUTH | ConvertFrom-Json
  "$($i): $($o.status)"; if ($o.status -ne "PENDING_PAYMENT") { break }; Start-Sleep -Milliseconds 400
}
```

- [ ] Checkout returns `202` + a `orderId`. **Copy the `orderId`** — you need it in §4.
- [ ] The order reaches **`PAID`** within a few polls.

> For a richer dashboard/log picture, also run the **declined** (`4000000000000002`) and **insufficient-funds** (`4000000000009995`) cards from `qa-walkthrough.md` §11–12. That gives you `payments_total{outcome=...}` and `reservations_total{status=RELEASED}` dimensions to see.

---

## 3. Phase 18a — metrics on the Grafana dashboard

1. Open `http://localhost:3000`, log in `admin`/`admin` (skip or set a new password at the prompt).
2. **Dashboards → ShopSphere Overview** (auto-provisioned).

- [ ] **orders_placed_total** panel shows a non-zero count matching the number of checkouts you ran.
- [ ] **payments_total** shows data, split by `outcome` (run declined/insufficient cards to see more than one series).
- [ ] **reservations_total** shows `held` / `confirmed` (and `released` if you ran a failing card).
- [ ] **checkout_latency_seconds** shows histogram data (count > 0).

Sanity-check the source if a panel is empty:

```powershell
# Prometheus is scraping the app?  → "up" should be 1 for job "shopsphere"
curl.exe -s "http://localhost:9090/api/v1/query?query=up" | ConvertFrom-Json | ConvertTo-Json -Depth 6
```

- [ ] Prometheus `up{job="shopsphere"}` is `1`. (If `0`, the app container isn't scrapeable — check `docker compose logs prometheus`.)

---

## 4. Phase 18b — search logs by `orderId` in Loki

1. In Grafana: **Explore** (compass icon) → datasource dropdown → **Loki**.
2. Query (paste your real `orderId`):

   ```
   {container="shopsphere-app"} |= "<ORDER_ID>"
   ```

3. Set the time range to **Last 15 minutes** and run.

- [ ] Results include lines from **multiple modules** for that one order. For a PAID order you should see (logger in each line):
  - [ ] **Ordering** — `Order placed with N line(s) …` (`OrderPlacement`)
  - [ ] **Reservation** — `Reservation granted for N item(s)` and later `Reservation confirmed for N item(s)` (`CatalogImpl`)
  - [ ] **Payment** — `Charge succeeded for order` (`PaymentOrderingConsumer`) and `Order marked PAID` (`PaymentEventsConsumer`)
- [ ] Each matched line is JSON with `orderId` as a **top-level field** (not just substring in the message) — confirms the MDC stamping, not an accidental match.

> Why a line filter and not a label: `orderId` is intentionally **not** a Loki label (a UUID label is unbounded cardinality). The raw JSON line is stored and `|=` substring-matched. See ADR-0018b.

Quick CLI cross-check (optional), bypassing Grafana:

```powershell
$q = [uri]::EscapeDataString('{container="shopsphere-app"} |= "' + $ORDER_ID + '"')
curl.exe -s "http://localhost:3100/loki/api/v1/query_range?query=$q" | ConvertFrom-Json `
  | Select-Object -ExpandProperty data | Select-Object -ExpandProperty result | Measure-Object
```

- [ ] Count is > 0 (Loki has lines for that order).

---

## 5. Phase 18b — Kafka UI

Open `http://localhost:8081`.

- [ ] The **shopsphere** cluster is listed and online.
- [ ] **Topics** include `ordering.events` and `payment.events`, each with a partition count.
- [ ] **Consumers** lists the groups — `payment.orderplaced`, `ordering.payment-events`, `catalog.ordering-terminal-events` — each with committed offsets (lag ~0 after the order settles).

---

## 6. Teardown

```powershell
docker compose --profile full down        # keep the db volume
docker compose --profile full down -v      # full clean slate (wipes pgdata)
```

---

## Pass criteria

All boxes in §3 (18a), §4 and §5 (18b) checked. If any panel is empty or the Loki search misses a module, that's a real gap — likely a module that didn't stamp `orderId` via `OrderLog`, or a scrape/ship misconfiguration. Don't mark the phase's manual-QA acceptance criterion done until this passes.

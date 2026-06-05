---
status: accepted
date: 2026-06-06
cites: PragProg, APoSD, XP, PoEAA
---

# 0018a — Prometheus + Grafana over the meters we already have

Phase 9 instrumented ShopSphere with four business meters — `orders_placed_total`, `payments_total`, `reservations_total`, and a `checkout_latency_seconds` timer — exposed only through `/actuator/metrics` (a JSON poke-and-hope endpoint). Phase 18a makes them *legible over time*: a Prometheus scrape endpoint, a Prometheus to store the series, and a provisioned Grafana dashboard. It adds **no new meters** and **no application code beyond a registry dependency**.

## Scrape, don't push; reuse, don't re-instrument

Adding `micrometer-registry-prometheus` and exposing `/actuator/prometheus` is the whole code change. The four meters were already there and correctly placed (Phase 9 put each counter *behind the dedupe gate* so redelivery never double-counts); a metrics backend is a read-only consumer of that work. **XP YAGNI** — the brief was explicitly "no new meters," and the dashboard is built from what exists. Resisting the urge to add request-rate or JVM panels keeps the dashboard about the *business* (orders, payments, reservations, checkout latency), which is what the four meters were chosen to express.

Prometheus **pulls** from the app rather than the app pushing. **PragProg / PoEAA** — pull keeps the application ignorant of its monitoring: the app exposes a text endpoint and knows nothing about Prometheus, retention, or Grafana. The monitoring topology can change (more scrapers, federation, remote-write) without touching a line of application code. The scrape target is the app's compose service name (`app:8080`), so the wiring is declarative config in `prometheus.yml`, not code.

## The dashboard targets real exported names, not guesses

Micrometer's Prometheus naming has a well-known trap: a counter whose Micrometer name already ends in `_total` can export as either `…_total` or `…_total_total` depending on client version, and a timer fans out into `_count`/`_sum`/`_bucket`/`_max`. Rather than guess the PromQL, the integration test (`PrometheusEndpointIT`) records each meter and reads the actual exposition output — confirming the exported families are `orders_placed_total`, `payments_total`, `reservations_total` (no doubled suffix, on Micrometer 1.13 with the new Prometheus client) and `checkout_latency_seconds_bucket`. The committed dashboard's queries are written against those verified names. **APoSD** — the test is the place that *knows* the metric names; the dashboard borrows that knowledge instead of duplicating an assumption that silently rots.

A second subtlety the test pinned down: `@SpringBootTest` disables metrics export by default (so test runs don't push to real backends), so the test needs `@AutoConfigureObservability` to see the endpoint. Production export is on by default and unaffected — recorded here because the asymmetry is surprising the first time it 404s a test.

## Provisioned, not clicked

Both the Prometheus datasource and the dashboard are **provisioned from committed files** (`observability/grafana/provisioning/`), so the dashboard is versioned with the code and survives a container wipe — no manual Grafana clicking to reproduce. **PragProg — automate the setup**: `docker compose --profile full up` brings up app + Prometheus + Grafana with the datasource and dashboard already wired. The observability stack lives under the `full` profile because it observes the containerised app; the default dev loop (Postgres + Kafka only) is unchanged, consistent with how Phase 10 gated the `app` service.

Grafana's admin password is intentionally left at the `admin/admin` default so its built-in first-login change prompt stays in force — a seeded credential to rotate, the same honest posture as the Phase-17 seeded admin.

## Consequences

Metrics are now queryable over time and visible on a dashboard with zero new instrumentation and no external dependency — the whole stack is local containers. `mvn verify` stays green; `PrometheusEndpointIT` guards the endpoint and, implicitly, the metric names the dashboard depends on. The one manual step is the acceptance check that the four panels light up with non-zero data, which requires running the QA walkthrough against the `full` stack to generate traffic — a manual QA step, like the cloud-phase walkthroughs, not something a unit test asserts. Phase 18b builds on this Grafana with logs (Loki) as a second data source.

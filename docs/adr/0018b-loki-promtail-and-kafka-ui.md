---
status: accepted
date: 2026-06-06
cites: PragProg, APoSD, PoEAA, XP
---

# 0018b — Loki + promtail for logs, Kafbat Kafka UI for the broker

Phase 18a made ShopSphere's **metrics** legible (Prometheus + a provisioned Grafana). Phase 18b does the same for its **logs** and its **broker**. The app already emits JSON logs (`LogstashEncoder`, since Phase 9) and already runs Kafka; what was missing was a place to *search* those logs by order and a window onto topics, partitions, and consumer offsets. This phase adds three containers — `loki`, `promtail`, `kafbat/kafka-ui` — and one small application change: every module now stamps `orderId` onto its log lines so a single Loki query follows an order across the whole system.

## Loki + promtail over ELK

The log backend is **Grafana Loki**, fed by **promtail**, not Elasticsearch + Logstash + Kibana. **XP YAGNI / PragProg "good enough":** Loki indexes only labels and stores the raw log line compressed — there is no full-text inverted index to provision, tune, or feed gigabytes of heap. For a single-node local stack whose logs are disposable (`docker compose down` wipes them), ELK is a database to operate where a `grep`-over-labels is all the acceptance check needs. Loki also *reuses the Grafana we already provisioned in 18a* — logs land as a second data source next to the metrics, so one pane explores both. ELK would have meant a second UI (Kibana) and a second mental model.

Promtail discovers the app container through the Docker engine API (the mounted socket) and ships its stdout verbatim. **The app stays ignorant of its log shipping** — the same posture as 18a's pull-based metrics (**PoEAA / PragProg**): no logback appender pointed at Loki, no network dependency compiled into the app, no code change to redirect logs elsewhere later. Logging topology is config, not code.

## Query by line filter, not by label — cardinality is the trap

The headline acceptance check is: search Loki for an `orderId` and get back every module's line for that order. The naive way to enable it is to parse the JSON in promtail and promote `orderId` to a Loki **label**. That is the classic Loki footgun: labels are the index, and a UUID-valued label has unbounded cardinality — one stream per order — which is exactly what Loki's docs warn destroys it. So promtail ships the **raw JSON line** with only a low-cardinality `container` label, and the query is a **line filter**:

```
{container="shopsphere-app"} |= "<orderId>"
```

Loki scans the (small, local) stream and matches the substring. **APoSD — the deep/cheap interface:** the expensive-to-misuse thing (labels) is kept tiny and stable; the flexible thing (arbitrary search) is pushed to query time where it costs nothing to be wrong.

## One helper owns the correlation field — so every module says "orderId" the same way

For the cross-module search to actually return Payment and Reservation lines (not just Ordering's), those modules have to put `orderId` on their log context. Before this phase only `OrderPlacement` did. Rather than copy-paste `MDC.put("orderId", …)/MDC.remove(…)` into four places — and risk one of them spelling the field differently or forgetting the `finally` — the field names live in **one deep module**, `common.OrderLog.withOrder(orderId[, customerId], body)` (**APoSD information hiding**). It stamps the MDC, runs the log statement, and always clears it. `OrderPlacement` was refactored onto it; `PaymentOrderingConsumer`, `PaymentEventsConsumer`, and `CatalogImpl` (reserve/confirm/release) now each emit one `orderId`-stamped line. `StructuredLogShapeTests` already pins the JSON field names the Loki query depends on; `OrderLogTests` pins that the helper stamps and always clears them.

Scope is deliberately tight — the helper wraps only the `log.info(...)` call, never downstream work — so an order's MDC never leaks onto an unrelated thread or nests with another order's context.

## Kafbat Kafka UI over Confluent Control Center

The broker window is **kafbat/kafka-ui** (the community fork of provectus/kafka-ui), not Confluent Control Center. **XP / zero-external-dependency:** Control Center is part of Confluent Platform — heavier, license-encumbered for production, and oriented at a Confluent cluster. Kafbat is a single Apache-2.0 container that points at any broker via `BOOTSTRAPSERVERS` and shows topics, partition counts, and consumer-group offsets — which is the whole acceptance criterion. It needs only the broker, but stays under the `full` profile so `docker compose up -d` keeps the dev inner loop minimal (Postgres + Kafka), consistent with how 18a gated the metrics stack and Phase 10 gated the app.

## Consequences

Logs are now searchable by order across Ordering, Payment, and Reservation in the same Grafana that shows the metrics, and the broker is browsable at `http://localhost:8081` — all local containers, zero external dependency, `mvn verify` stays green (the new `OrderLogTests` plus the existing suite). Two honest limits, both recorded so they don't surprise later:

- **The end-to-end Loki search is a manual QA step**, like 18a's "panels light up": it needs the `full` stack running and a QA walkthrough to generate an order, then `{container="shopsphere-app"} |= "<orderId>"` in Grafana. No unit test asserts the rendered Loki result.
- **"Outbox" coverage is the Ordering leg, not a separate logger.** ShopSphere's outbox is Spring Modulith's event-publication table, drained to Kafka by Modulith's own machinery — there is no app code there to stamp. Ordering's `Order placed` line is emitted in the *same transaction* as the outbox insert, so it is the outbox's correlation point; the externalised event itself carries `orderId` and is visible in Kafka UI. We did not fabricate an outbox logger to satisfy the checklist literally.

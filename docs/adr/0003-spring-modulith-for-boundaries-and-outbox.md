---
status: accepted
date: 2026-05-16
cites: DDD, PoEAA
---

# 0003 — Spring Modulith for module boundaries and the transactional outbox

We use **Spring Modulith** for two jobs: (1) enforcing module boundaries at compile-time via `package-info.java` + `ApplicationModule` annotations and `ApplicationModules.verify()` in a CI test, and (2) supplying a battle-tested **transactional outbox** so order-placement and event-publication share one Postgres transaction. Without Modulith we'd hand-roll either Archunit boundary tests or an outbox table + scheduler, both of which are real work and easy to get wrong.

**DDD** says bounded contexts must be enforced, not aspirational — Modulith makes them mechanical. **PoEAA's Unit of Work / outbox pattern** is exactly what we're getting: the `OrderPlaced` event row is inserted in the same TX as the `orders` row, and a separate process drains the outbox to Kafka with at-least-once semantics. Consumers MUST be idempotent (dedupe on `eventId`); this is documented in `CONTEXT.md` and enforced by a `processed_events` table per consumer.

Trade-off: locks us to Spring's ecosystem timeline. Reversible by replacing Modulith with hand-rolled ArchUnit + custom outbox, which is annoying but contained.

---
status: accepted
date: 2026-05-16
cites: XP, PragProg
---

# 0005 — Plain JSON Kafka serde, no schema registry

Kafka events are serialized as plain JSON with a top-level `eventId` (UUID), `eventType` (string discriminator), and `occurredAt` (ISO-8601). No Avro, no Protobuf, no Confluent Schema Registry. **XP YAGNI**: we have one producer and one consumer per event in MVP — a schema registry's contract-evolution and compatibility-check value is zero until multiple teams or services share events. **PragProg reversibility**: every event payload is small and stable; if a future ADR adopts Avro, it can be retro-fitted by writing a one-shot migrator and a dual-writer for the transition window.

Documenting this so the next engineer doesn't "fix" the missing registry on autopilot — JSON is deliberate, not an oversight.

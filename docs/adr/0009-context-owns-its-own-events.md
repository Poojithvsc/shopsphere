---
status: accepted
date: 2026-05-16
cites: DDD, PragProg
---

# 0009 — Each bounded context publishes events about its own concepts only

Catalog does **not** subscribe to `PaymentSucceeded` / `PaymentFailed`. Instead, Ordering bridges payment outcomes into its own vocabulary and emits `OrderPaid` and `OrderCancelled`; Catalog listens to those to confirm or release its **StockReservation**.

The naive wiring — Payment publishes payment events that everyone consumes — leaks payment vocabulary into Catalog, which then has to reason about cards and declines instead of its own concepts (Products and Reservations). **DDD bounded contexts** require each context to expose its own language outward; events are part of that language. **PragProg orthogonality**: if we replace the Payment Simulator with a real provider tomorrow, Catalog code does not need to change — it never knew payment existed.

Cost: one extra event hop per outcome (Payment → Ordering → Catalog instead of Payment → Catalog directly). Worth it. Reversibility: low — undoing this would re-leak payment vocabulary across the system and is exactly the kind of architectural regression this ADR exists to prevent.

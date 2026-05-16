---
status: accepted
date: 2026-05-16
cites: DDD, APoSD, PragProg
---

# 0004 — Stock Reservation as a first-class aggregate; synchronous `reserve` at checkout

Inventory holds are modeled as a **StockReservation** aggregate (`reservationId`, `orderId`, `productId`, `qty`, `status: HELD | CONFIRMED | RELEASED`), not as `reserved_qty` columns on Product. Verbs are **`reserve` / `confirm` / `release`** — "confirm" instead of "commit" to avoid clashing with database-transaction commit. Confirmation happens when Catalog consumes `OrderPaid`; release happens when Catalog consumes `OrderCancelled`.

`Ordering.placeOrder()` invokes `Catalog.reserve(items)` **synchronously** inside the same Postgres transaction as the `Order` insert and the outbox row insert. Stock allocation must be linearized — two carts cannot reserve the last unit. The alternative (async reservation via a saga with `ReservationRequested → ReservationGranted/Denied`) is the textbook microservices answer and pure overkill here: the operation completes in single-digit milliseconds inside the same DB.

**DDD** value-objects-and-aggregates: a Reservation has identity, lifecycle, and invariants; "two columns on Product" buries them. **APoSD deep module**: `reserve / confirm / release` is a three-method interface hiding `SELECT FOR UPDATE`, idempotency, and audit. **PragProg orthogonality**: payment failure and stock release become independent — Catalog never knows what payment is. Trade-off: one extra table and a richer migration; recoverable from columnar by writing a backfill if we ever change our minds.

---
status: accepted
date: 2026-05-16
cites: DDD
---

# 0002 — Four bounded contexts; Cart inside Ordering; Identity owns Customer

We split ShopSphere into four bounded contexts: **Catalog** (Products, Stock Reservations), **Identity** (User, Customer, RefreshToken), **Ordering** (Cart, Order), **Payment** (PaymentSimulator + outbox listeners). Each context maps to one Spring Modulith module and one Postgres schema; cross-schema joins are forbidden and contexts reference each other only by id or by domain events.

**Cart lives inside Ordering**, not as its own context — Cart and Order share a customer-purchase narrative and the same aggregate family; splitting them would create two contexts that only ever talk to each other. **Identity owns Customer** (both materialized at registration, single TX) — the alternative (lazy Customer creation on first checkout) buys nothing in MVP and introduces a "registered but not yet Customer" state with no real semantics.

Cites **DDD bounded contexts**: each context owns its own language and data, and the language map (see `CONTEXT.md`) is the source of truth for what each context is allowed to mean by "Order", "Customer", "Stock". The decision is moderately hard to reverse — moving Customer from Identity to Ordering later would require a migration and an event-flow rewrite, but the FK shape and Modulith boundaries make it surgical rather than catastrophic.

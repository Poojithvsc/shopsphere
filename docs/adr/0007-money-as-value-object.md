---
status: accepted
date: 2026-05-16
cites: PoEAA, DDD
---

# 0007 — Money as a value object

All monetary quantities — product prices, line totals, order totals — flow through a `Money` value object with two fields: `amount: BigDecimal` and `currency: Currency`. Arithmetic across mismatched currencies throws by construction; equality is structural; the type is immutable. Persistence: `numeric(19,4) amount` + `varchar(3) currency` on every owning row.

The naive alternative (`BigDecimal` everywhere, currency tracked separately) is exactly the **PoEAA Money pattern**'s motivating cautionary tale — `total + shippingFee` compiles fine even if one is USD and the other INR. The `double` alternative is a non-starter for monetary arithmetic. **DDD value object** orthodoxy: `Money` has no id and no lifecycle; two `Money(100, INR)` instances are the same `Money`.

MVP uses INR exclusively, but the type carries currency from day one — adding USD later is a Flyway migration plus a UI field, not a schema-wide refactor.

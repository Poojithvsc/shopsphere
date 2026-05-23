---
project: shopsphere
type: context
status: populated
updated: 2026-05-23
---

# ShopSphere â€” CONTEXT

> Source of truth for naming and concepts. Names here must match names in Java packages, classes, and database tables. When code and CONTEXT disagree, one of them is wrong â€” fix it.
>
> Mirrored to `D:\shopsphere-project\code\docs\CONTEXT.md` on every change.

## Guiding texts

Every design decision is judged against these. ADRs must cite at least one.

| Source | We pull from it |
|---|---|
| **Patterns of Enterprise Application Architecture** (Fowler) | Repository, Unit of Work, Service Layer, DTO, Domain Model, Data Mapper, Money pattern |
| **A Philosophy of Software Design** (Ousterhout) | Deep modules, information hiding, strategic vs tactical programming |
| **Domain-Driven Design** (Evans) | Ubiquitous language, bounded contexts, aggregates, value objects, domain events |
| **Extreme Programming Explained** (Beck) | TDD, red-green-refactor, small releases, YAGNI |
| **The Pragmatic Programmer** (Hunt/Thomas) | DRY, orthogonality, tracer bullets, reversibility |

## Language

**Customer**:
A person who places **Orders**, owns a **Cart**, and provides a shipping address at checkout.
_Avoid_: shopper, buyer, client, account.

**User**:
The authentication identity (email + password + tokens) belonging to a **Customer**. Lives strictly inside the `identity` module. 1:1 with **Customer** in MVP, but conceptually distinct: identity â‰  domain actor. **Identity owns both `User` and `Customer`** â€” they are materialized together at registration in a single transaction.
_Avoid_: account, login, principal.

**Cart**:
The mutable collection of **Line Items** a **Customer** is currently considering. Exactly one **Cart** per **Customer**, for life. Cleared on successful checkout; not restored on failed payment.
_Avoid_: basket, bag, wishlist.

**Order**:
An immutable snapshot of a **Cart** at checkout time, plus a shipping address and a payment status. Owns its own copies of name + unit price + qty per **Line Item** so price changes in **Catalog** never alter history.
_Avoid_: purchase, transaction, receipt.

**Line Item**:
One row of a **Cart** or **Order**: a **Product** reference + qty (+ snapshotted name + unit price when inside an **Order**).
_Avoid_: cart item, order item, basket entry.

**Product**:
A sellable item in the **Catalog**. Has a name, description, unit price (**Money**, INR), and `availableQty`. Seeded via Flyway; no admin CRUD in MVP.
_Avoid_: SKU (until variants exist), item, listing.

**Stock Reservation**:
A first-class aggregate representing inventory held against a specific **Order**. Fields: `reservationId`, `orderId`, `productId`, `qty`, `status` (`HELD`, `CONFIRMED`, `RELEASED`). Created at checkout, **confirmed** on payment success (qty leaves the system), **released** on payment failure (qty returns to `availableQty`).
_Avoid_: hold, lock, allocation, reserved_qty column.

**Money**:
A value object: `amount: BigDecimal` + `currency: Currency`. Arithmetic across mismatched currencies is forbidden by construction. MVP uses INR exclusively.
_Avoid_: price, amount, decimal.

**Order Status**:
The state of an **Order**. MVP states: `PENDING_PAYMENT` (created, awaiting payment outcome), `PAID` (terminal success), `CANCELLED` (terminal failure, only reachable from `PENDING_PAYMENT`). `FULFILLED` is deliberately absent â€” it will reappear when real warehouse + shipping exist.
_Avoid_: state, phase, stage.

**Payment Outcome**:
The result of running a card through the **Payment Simulator**. A sealed type with two variants: `Succeeded(amount)` and `Failed(reason)`. The `reason` carries a **Payment Failed Reason**.
_Avoid_: PaymentResult, PaymentStatus (collides with Order Status), failure.

**Payment Failed Reason**:
Why a payment failed. Exactly one of `DECLINED` or `INSUFFICIENT_FUNDS`. Both cancel the **Order**; they exist as distinct values because the test card numbers do (`4000 0000 0000 0002` â†’ `DECLINED`; `4000 0000 0000 9995` â†’ `INSUFFICIENT_FUNDS`) and consumers may want different downstream behaviour later.
_Avoid_: PaymentFailure, error code.

**Processed Event**:
A `(consumer_id, event_id)` row in a module's `processed_events` table. Inserted in the same transaction as the consumer's side effect; on duplicate-key the consumer skips work. The dedupe boundary that makes at-least-once Kafka delivery safe.
_Avoid_: consumed_event, seen_event, idempotency_key.

## Relationships

- A **Customer** has exactly one **User** (MVP), owns exactly one **Cart**, and may own zero or more **Orders**.
- A **Cart** contains zero or more **Line Items**.
- A **Cart** is *snapshotted into* a new **Order** on successful checkout; the **Cart** is then cleared.
- An **Order** contains one or more **Line Items**, immutable after creation.
- A failed payment cancels the **Order** but does not restore the **Cart**.

## Example dialogue

> **Dev:** "When a **Customer** places an **Order**, do we copy their email onto it?"
> **Domain expert:** "No â€” the **Order** references the **Customer**'s id. The **User**'s email is an identity concern, not an order concern."

## Flagged ambiguities

- "shopper", "user", and "customer" were used interchangeably in the PRD â€” resolved 2026-05-16: **Customer** is the domain actor, **User** is the identity row. "Shopper" is retired.

---

## Bounded contexts

| Context | Responsibility | Aggregates |
|---|---|---|
| **Catalog** | Owns the inventory truth. Lists **Products**, runs **Stock Reservations**. | `Product`, `StockReservation` |
| **Identity** | Owns authentication. Issues access + refresh tokens, hashes passwords. | `User`, `RefreshToken` |
| **Ordering** | Owns the **Cart** and the **Order** lifecycle. Bridges the **Customer** to **Payment** + **Catalog**. | `Cart`, `Order` |
| **Payment** | Owns the simulated card-processing rules. | (no aggregate â€” stateless `PaymentSimulator` + outbox listener) |

Cross-schema joins are forbidden. Contexts reference each other only by id (`CustomerId`, `OrderId`, `ProductId`) and by published events.

## Domain events

Each context publishes events about *its own* concepts only.

**Emitted by Ordering** (topic `ordering.events`):
- `OrderPlaced` â€” a new **Order** entered `PENDING_PAYMENT`. Payload: `orderId`, `customerId`, `total` (**Money**), `cardNumber` (MVP only â€” passed through to **Payment Simulator**; tokenise before production), `lines` (snapshotted `{productId, name, unitPrice, qty}`). Consumed by: **Payment**.
- `OrderPaid` â€” an **Order** transitioned to `PAID`. Payload: `orderId`, `customerId`. Consumed by: **Catalog** (confirms its **Stock Reservation**).
- `OrderCancelled` â€” an **Order** transitioned to `CANCELLED`. Payload: `orderId`, `customerId`, `reason` (string carrying the **Payment Failed Reason** value: `DECLINED` or `INSUFFICIENT_FUNDS`). Consumed by: **Catalog** (releases its **Stock Reservation**).

**Emitted by Payment** (topic `payment.events`):
- `PaymentSucceeded` â€” a card cleared the **Payment Simulator**. Payload: `orderId`, `customerId`, `amount` (**Money**). Consumed by: **Ordering** (drives `OrderPaid`).
- `PaymentFailed` â€” a card was declined or had insufficient funds. Payload: `orderId`, `customerId`, `reason` (`DECLINED` | `INSUFFICIENT_FUNDS`). Consumed by: **Ordering** (drives `OrderCancelled`).

Every event has top-level `eventType` (string discriminator), `eventId` (UUID), and `occurredAt` (Instant). Consumers MUST dedupe by `eventId` via a per-consumer `<schema>.processed_events(consumer_id, event_id)` table; the dedupe insert lives in the same transaction as the state mutation so a rollback unwinds it.

## Example dialogue

> **Dev:** "Catalog releases stock when payment fails, right?"
> **Domain expert:** "Catalog doesn't know about payments. Catalog releases stock when it sees `OrderCancelled` from **Ordering**. Whether the cancellation was caused by a declined card or by something else is **Ordering**'s business, not Catalog's."

## Flagged ambiguities

- "shopper" / "user" / "customer" used interchangeably in PRD â€” resolved 2026-05-16: **Customer** = domain actor, **User** = identity row.
- "PaymentCompleted" was ambiguous (success or just "ran") â€” resolved 2026-05-16: renamed **PaymentSucceeded**.
- "stock reserved as columns" vs "reservation as entity" â€” resolved 2026-05-16: **Stock Reservation** is a first-class aggregate.
- `FULFILLED` order state â€” resolved 2026-05-16: dropped for MVP, will return when real fulfillment exists.

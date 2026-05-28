# Learning ShopSphere — a guided tour of how (and why) it's built

This is the document I'd hand to a friend who wants to *understand* this project — not just run it. It explains what each piece is for, why it was chosen, where the named patterns come from, and how to teach the ideas to someone else. It's grounded entirely in code and decisions that exist in the repo; nothing is invented. If something is missing or aspirational, it's in the **"What's not here yet"** section at the end so you can spot it for what it is.

> **How to use this doc.** Read sections 1–4 once, top to bottom, to build the mental model. Then use the rest as a reference — jump to "Where Kafka shows up" or "The checkout flow" when you need to look something up. The diagrams referenced (`vault/projects/shopsphere/diagrams/*.excalidraw`) are the visual half of this story; open them in Obsidian or VS Code (Excalidraw extension) alongside this text.

---

## Table of contents

1. [What ShopSphere is and what makes it different](#1-what-shopsphere-is-and-what-makes-it-different)
2. [The big idea: a modulith, not microservices](#2-the-big-idea-a-modulith-not-microservices)
3. [The five books, made concrete](#3-the-five-books-made-concrete)
4. [The four bounded contexts (and what each owns)](#4-the-four-bounded-contexts-and-what-each-owns)
5. [The data layer — Flyway, schemas, Money](#5-the-data-layer--flyway-schemas-money)
6. [The HTTP layer — REST, JWT, Spring Security](#6-the-http-layer--rest-jwt-spring-security)
7. [Where Kafka shows up — every place, with code locations](#7-where-kafka-shows-up--every-place-with-code-locations)
8. [The transactional outbox + per-consumer dedupe](#8-the-transactional-outbox--per-consumer-dedupe)
9. [Walking through a single checkout, end to end](#9-walking-through-a-single-checkout-end-to-end)
10. [Phases and tracer bullets — why this order](#10-phases-and-tracer-bullets--why-this-order)
11. [How testing was done (and why no mocks for domain code)](#11-how-testing-was-done-and-why-no-mocks-for-domain-code)
12. [Observability that's actually there](#12-observability-thats-actually-there)
13. [How to explain this to someone else (a teaching script)](#13-how-to-explain-this-to-someone-else-a-teaching-script)
14. [What's NOT here yet (honest gaps)](#14-whats-not-here-yet-honest-gaps)
15. [Further reading + glossary](#15-further-reading--glossary)

---

## 1. What ShopSphere is and what makes it different

ShopSphere is a small online-store backend — products, accounts, a cart, checkout, simulated card payments. The interesting bit is not *what* it does (every Spring Boot tutorial does this) but *how* it's built:

- **One running process, not five.** Where a typical "Spring microservices" tutorial would split this into `user-service`, `product-service`, `order-service`, etc., ShopSphere keeps everything in a single Spring Boot app. Boundaries are still real — they're enforced by **Spring Modulith** in tests — but they exist *inside* the same JVM. This is called a **modulith** (modular monolith).
- **Every architectural decision is written down.** There are nine **ADRs** (Architecture Decision Records) in `docs/adr/` that name the trade-off, name which book the technique comes from, and name what would force a re-decision later.
- **The build was sliced into 9 vertical phases.** Each phase did "schema → domain → REST → tests → CI" for one small feature, and each one ends with a working, shippable app. That's the **tracer bullet** approach from *The Pragmatic Programmer*; we'll cover what it means in §10.
- **The names match.** Every important concept (Customer, Cart, Order, Stock Reservation, Money) appears identically in `CONTEXT.md`, in Java class names, and in Postgres tables. That alignment is from DDD (*Domain-Driven Design*) and is called the **ubiquitous language**.

If you've ever read a Spring Boot tutorial and felt "I can copy this but I don't know *why* anything is done this way" — ShopSphere is the opposite. Every choice is annotated.

---

## 2. The big idea: a modulith, not microservices

A natural first instinct in 2026 is "ecommerce backend = microservices." That's what the **MicroMart** companion project in `D:\Tinku anna project\project after PEAA and springboot\` does: five separate services (`api-gateway`, `eureka-server`, `user-service`, `product-service`, `order-service`), each its own JVM, each its own database, talking over HTTP and Kafka. ShopSphere deliberately does **not** do that.

### Why not microservices for ShopSphere

| What microservices buy you | Does ShopSphere need it? |
|---|---|
| Independent deploys per team | No team — one developer |
| Per-service scaling | No scale yet |
| Polyglot (Python here, Java there) | Don't need it |
| Fault isolation across services | Doesn't exist if all 5 share one Postgres anyway (MicroMart does) |
| Independent data ownership | We get this *inside* one app via Postgres schemas |

| What microservices cost you | Painful here? |
|---|---|
| Network calls between services | Yes — instant latency + new failure modes |
| Distributed transactions / sagas | Yes — every cross-service flow needs careful retry logic |
| Service discovery (Eureka), gateways, mTLS, etc. | Yes — extra moving parts |
| Operational complexity (5 deploys, 5 log streams) | Yes — for one person |

ADR-0001 calls this out using two named ideas:
- **Ousterhout's "strategic programming"** (from *A Philosophy of Software Design*) says: spend complexity budget where it pays back. Microservices' payback is zero here, so the budget should go to learning DDD and the deep patterns instead.
- **Hunt & Thomas's "reversibility"** (from *The Pragmatic Programmer*) says: pick the path that's easier to back out of. **Splitting one modulith into services later is mechanical work** (each module has its own schema and only talks via events); **merging five services back into one because you regret the split is not.** So we start where we can move fastest.

### What a modulith looks like

```
┌──────────────────────────────────────────────────────────────┐
│  Spring Boot app  (ONE running JVM, port 8080)               │
│  ┌─────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐        │
│  │ catalog │  │ identity │  │ ordering │  │ payment │        │
│  └─────────┘  └──────────┘  └──────────┘  └─────────┘        │
│       │            │             │             │             │
│   schema:      schema:        schema:       schema:          │
│   catalog     identity       ordering      payment           │
└──────────────────────────────────────────────────────────────┘
         │                            │
   ┌─────▼──────┐              ┌──────▼──────┐
   │ Postgres   │              │ Kafka       │
   │  (4 schemas │              │ (2 topics)  │
   │   in 1 DB) │              └─────────────┘
   └────────────┘
```

The four modules are in the same process and the same garbage-collected heap. A direct Java method call from `ordering` to `catalog` is fine (cheap, transactional, no network). But: those calls are **only allowed through the public Java interface of the other module** — never reaching into another module's internals. **Spring Modulith** enforces this at build time (we'll see how in §11).

The visual is in `vault/projects/shopsphere/diagrams/level-3-containers.excalidraw` and `module-dependencies.excalidraw`.

---

## 3. The five books, made concrete

Every ADR in `docs/adr/` cites at least one of these five books. The point is that the project isn't "do whatever Spring Boot tutorials say"; it's "pick a named technique from a named source." Here's the cheat sheet:

| Book (short name) | Idea it gives us | Where you see it in this project |
|---|---|---|
| **PoEAA** — *Patterns of Enterprise Application Architecture* (Fowler) | Repository, Service Layer, DTO, **Money pattern**, **Unit of Work / outbox** | `ProductRepository`, `OrderRepository`, `CheckoutService`, `Money` value object, the Modulith outbox |
| **APoSD** — *A Philosophy of Software Design* (Ousterhout) | Deep modules (small interface hiding lots of implementation); strategic vs tactical programming; "information hiding" | `Catalog.reserve / confirm / release` — three methods hide `SELECT FOR UPDATE`, idempotency, and audit |
| **DDD** — *Domain-Driven Design* (Evans) | Ubiquitous language, bounded contexts, aggregates, value objects, domain events | The 4 modules; `CONTEXT.md`; `Cart`, `Order`, `StockReservation` aggregates; `Money` VO; `OrderPlaced` etc. |
| **XP** — *Extreme Programming Explained* (Beck) | TDD, red-green-refactor, small releases, **YAGNI** ("you aren't gonna need it") | Each phase is one PR; no schema registry (YAGNI in ADR-0005); no `FULFILLED` order state (YAGNI in ADR-0008) |
| **Pragmatic Programmer** (Hunt & Thomas) | DRY, **orthogonality**, **tracer bullets**, **reversibility** | The 9 phases are tracer bullets; ADR-0009 calls out orthogonality (payment vocabulary doesn't leak to catalog) |

You don't need to have read all five end-to-end. You *do* need to recognize the names so when someone asks "why is `Money` a class and not just a `BigDecimal`?", you can say "PoEAA Money pattern — see ADR-0007."

### A pocket glossary of the named techniques

These all show up in this codebase; each is a thing you'll get asked about in interviews.

| Technique | One-line explanation | Where in ShopSphere |
|---|---|---|
| **Aggregate** (DDD) | A small cluster of objects you only modify together; you save and load it as one unit; one of them is the "root" you reference from outside | `Cart` (root) + `CartLineItem`s; `Order` + `OrderLineItem`s; `StockReservation` |
| **Value Object** (DDD / PoEAA Money) | Has no identity, no lifecycle — two instances with the same data ARE the same thing. Immutable. | `Money` |
| **Bounded Context** (DDD) | A piece of the system that owns one consistent meaning of a word ("order" in Ordering means one thing; the same word doesn't have to mean the same thing in another context) | catalog · identity · ordering · payment |
| **Ubiquitous Language** (DDD) | The names domain experts use for ideas — and *the same names appear in the code*. | `CONTEXT.md` lists them all; class names match |
| **Repository** (PoEAA) | A class that *looks* like a collection of objects but reads/writes them in a database | `ProductRepository`, `OrderRepository`, `CartRepository`, `RefreshTokenRepository` |
| **Service Layer** (PoEAA) | A thin layer of orchestration code (no business rules of its own) that REST controllers call into | `CheckoutService`, `CartService`, `AuthService` |
| **Domain Event** (DDD) | A fact that something happened, expressed in the domain's language | `OrderPlaced`, `OrderPaid`, `OrderCancelled`, `PaymentSucceeded`, `PaymentFailed` |
| **Transactional Outbox** (PoEAA Unit-of-Work-ish) | When you want to change the DB AND publish a message, you write *both* into the same DB transaction; a separate process forwards the message later. Stops "DB committed but message lost." | Spring Modulith's outbox; visible in `event_publication` table |
| **State Machine** | An explicit list of "which states can transition to which other states" | `OrderStateMachine` — `PENDING_PAYMENT → PAID` or `→ CANCELLED`; nothing else |
| **Tracer Bullet** (Pragmatic Programmer) | A thin slice of code that touches every layer end-to-end, runs, and proves the wiring works — *then* you fatten the slice up | Each of the 9 phases |
| **Deep Module** (APoSD) | A class/module with a small surface (few methods) hiding a lot of implementation behind it | `Catalog.reserve / confirm / release` |
| **Orthogonality** (Pragmatic Programmer) | Two pieces of the system don't know about each other and can change independently | Catalog code mentions no payment words; payment never imports `Order` |
| **YAGNI** (XP) | Don't build it until you need it | No schema registry (ADR-0005), no `FULFILLED` state (ADR-0008) |
| **Reversibility** (Pragmatic Programmer) | Prefer choices you can undo later | Modulith over microservices (ADR-0001); JSON over Avro (ADR-0005) |

---

## 4. The four bounded contexts (and what each owns)

Think of bounded contexts as **departments in a company**. Each one has its own vocabulary, its own filing cabinet, and its own outgoing memos. Two departments coordinate by *sending memos to each other*, not by reaching into each other's filing cabinets.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            ShopSphere modulith                              │
│                                                                             │
│  ┌──────────────────────┐    ┌──────────────────────┐                       │
│  │   identity           │    │   catalog            │                       │
│  │   (the front desk)   │    │   (the warehouse)    │                       │
│  │                      │    │                      │                       │
│  │   - User             │    │   - Product          │                       │
│  │   - Customer         │    │   - StockReservation │                       │
│  │   - RefreshToken     │    │     (reserve /       │                       │
│  │   - JWT issuing      │    │      confirm /       │                       │
│  │                      │    │      release)        │                       │
│  └──────────────────────┘    └──────────────────────┘                       │
│                                                                             │
│  ┌──────────────────────┐    ┌──────────────────────┐                       │
│  │   ordering           │    │   payment            │                       │
│  │   (the cashier)      │    │   (the card reader)  │                       │
│  │                      │    │                      │                       │
│  │   - Cart             │    │   - PaymentSimulator │                       │
│  │   - Order            │    │   - (no DB table     │                       │
│  │   - OrderStateMachine│    │      of its own —    │                       │
│  │   - Checkout         │    │      stateless)      │                       │
│  │   - Outbox publisher │    │                      │                       │
│  └──────────────────────┘    └──────────────────────┘                       │
│                                                                             │
│  ┌──────────────────────────────────────────────────────┐                   │
│  │   common  (shared kernel — only the Money type)      │                   │
│  └──────────────────────────────────────────────────────┘                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

Each module owns **one Postgres schema** with the same name. Cross-schema joins are forbidden. Modules reference each other by **id** (a UUID — "I have customer `xyz`'s order") and via **events** (Kafka), never by foreign keys across schemas.

### Why these four and not more or fewer (ADR-0002)

- **Cart is inside Ordering**, not its own context. Why? Cart and Order share a customer-purchase narrative ("the cart turns into the order at checkout"). Two contexts that only talk to each other are an artificial split.
- **Customer is inside Identity** (and is created at registration in the same transaction as the User). Why? The alternative — "lazy Customer creation when they first check out" — buys nothing and adds a confusing "registered but not yet a Customer" state.
- **Payment has no aggregate of its own.** It's the only context that's just a stateless service plus event listeners. It doesn't need to persist anything; the order itself stores the outcome.

### The "what's allowed to talk to what" rules

Three rules, enforced by `ApplicationModules.verify()` (Spring Modulith) in the test suite:

1. **Modules may depend on `common` only.** `Money` is the only thing in `common`.
2. **Modules may NOT import each other's internal classes.** Only the public Java interface (e.g. `Catalog` interface in `catalog/Catalog.java`).
3. **Cross-module communication for stateful flows uses Kafka events**, not direct method calls — even though the modules are in the same JVM. Direct calls would couple the contexts too tightly. *The one exception* is `Ordering → Catalog.reserve()` at checkout time, which is **deliberately synchronous** because stock allocation must be linearized (two carts cannot both reserve the last unit — ADR-0004).

---

## 5. The data layer — Flyway, schemas, Money

### One Postgres database, four schemas

A schema in Postgres is essentially a namespace for tables. The four schemas are:

| Schema | Tables (highlights) |
|---|---|
| `catalog` | `products`, `stock_reservations`, `processed_events`, `event_publication` (Modulith's outbox) |
| `identity` | `users`, `customers`, `refresh_tokens` |
| `ordering` | `carts`, `cart_line_items`, `orders`, `order_line_items`, `processed_events` |
| `payment` | `processed_events` (that's it — Payment is stateless) |

This is the textbook **database-per-bounded-context** rule, achieved cheaply (one Postgres instance, four namespaces). When/if a module is later extracted into its own service, it already owns its schema; you just move the schema to its own database.

### Flyway runs the migrations, Hibernate validates

Look at `application.yml`:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate          # ← Hibernate is NOT allowed to change the schema
  flyway:
    enabled: true
    schemas: catalog,identity,ordering,payment
    locations: classpath:db/migration/catalog, ... (one folder per schema)
```

Two things are happening:

1. **Flyway** (`V1__create_products.sql`, `V2__create_users_and_customers.sql`, … `V10__create_catalog_processed_events.sql`) is the *only* tool that creates or alters tables. Migrations are versioned `.sql` files that ship with the code; you can replay them on a fresh DB and get the same schema every time.
2. **Hibernate's `ddl-auto: validate`** mode means at startup Hibernate checks "do the tables and columns match what my `@Entity` classes say?" If they don't, the app refuses to start. This is the opposite of `ddl-auto: update` (which is what MicroMart uses, and which is a known anti-pattern in production because it silently makes schema changes you didn't review).

### The Money value object (ADR-0007, PoEAA Money pattern)

Every monetary value in ShopSphere is a `Money` object:

```java
new Money(new BigDecimal("8499.00"), Currency.getInstance("INR"))
```

Two fields. Immutable. Adding `Money(100, USD) + Money(100, INR)` throws — by construction. Two `Money(100, INR)` instances are equal (value object).

Why bother? Because the alternative (`BigDecimal amount` and a `String currency` somewhere far away) lets you write `total + shippingFee` even when one is USD and one is INR. The compiler can't help. With `Money` the compiler *does* help, because there's no way to add mismatched currencies — the method throws.

In the DB it lives as two columns: `unit_price_amount NUMERIC(19,4)` + `unit_price_currency VARCHAR(3)`. MVP only uses INR but the currency column exists from day one — so adding USD later is a Flyway insert, not a refactor.

---

## 6. The HTTP layer — REST, JWT, Spring Security

### The endpoints

All under `/api/v1/...`. Everything except `/auth/register|login|refresh` requires a `Authorization: Bearer <accessToken>` header. The full map:

| Method | Path | What it does |
|---|---|---|
| `POST` | `/api/v1/auth/register` | Create a `User` + `Customer` together in one TX |
| `POST` | `/api/v1/auth/login` | Verify credentials → issue access JWT + refresh token |
| `POST` | `/api/v1/auth/refresh` | Rotate refresh token, mint a new access JWT |
| `POST` | `/api/v1/auth/logout` | Revoke the current refresh token |
| `GET` | `/api/v1/products` | List products (the seeded 3) |
| `GET` | `/api/v1/products/{id}` | One product |
| `GET` | `/api/v1/cart` | View cart (auto-creates one) |
| `POST` | `/api/v1/cart/items` | Add a line item |
| `PATCH` | `/api/v1/cart/items/{productId}` | Change qty (0 removes) |
| `DELETE` | `/api/v1/cart/items/{productId}` | Remove |
| `POST` | `/api/v1/orders` | Checkout — turn the cart into an Order |
| `GET` | `/api/v1/orders` | List the caller's orders |
| `GET` | `/api/v1/orders/{id}` | One order |

You can explore all of these in Swagger UI: `http://localhost:8080/swagger-ui.html`.

### The JWT + refresh token pair (ADR-0006)

This is one of the things that's genuinely well done in this project — most demos get it wrong.

- **Access token** = a JWT, signed HS256, contains `userId` + `customerId` in claims, **15 minute lifetime**. It's *stateless* — the server doesn't store it. Every request: server verifies signature + expiry → done. Zero DB hits.
- **Refresh token** = an opaque random string, **7 day lifetime**, stored in `identity.refresh_tokens` as a **hash** (BCrypt). The raw string is sent to the user once at login; only the hash lives in the DB.
- **Rotation**: every successful `/auth/refresh` invalidates the presented refresh token and issues a fresh one.
- **Reuse detection**: if a *used* (rotated) refresh token is presented again, the system assumes it was stolen. It revokes **every** refresh token in that user's "family" — including the current valid one. The legitimate user is forced to log in again. This is the textbook OAuth2 "refresh token rotation with reuse detection" pattern.

Why this design?
- A pure long-lived JWT is fast but **un-revocable** until expiry — a stolen token works until it dies naturally.
- A pure DB-session lookup is revocable but adds a DB read to every request.
- The hybrid: requests are fast (JWT, stateless) and revocation works (drop the refresh row).

ADR-0006 specifically picks HS256 (symmetric) over RS256 (asymmetric) because there's only one signer and one verifier (same process). RS256 would be needed if the API gateway were extracted from the monolith — and the ADR says "revisit then."

### Spring Security configuration

In `SecurityConfig.java` (identity module):

```
permitAll:    /api/v1/auth/register, /login, /refresh, /actuator/**, swagger paths
authenticated: everything else
```

The `JwtAuthenticationFilter` runs on every request: pulls the `Authorization` header, verifies the JWT, extracts the `customerId`, and stuffs an `AuthenticatedPrincipal` into Spring's `SecurityContextHolder`. After that, controllers can call `currentCustomerId()` to know who's asking.

---

## 7. Where Kafka shows up — every place, with code locations

Kafka does two distinct jobs in this app, and both are subtle. Here's the full map of *every* Kafka touch-point.

### The two topics

| Topic | Who writes to it | Who reads from it |
|---|---|---|
| `ordering.events` | the **ordering** module (via the Modulith outbox) | the **payment** module (only for `OrderPlaced`), the **catalog** module (only for `OrderPaid`/`OrderCancelled`) |
| `payment.events` | the **payment** module (via the Modulith outbox) | the **ordering** module |

Both topics are auto-created on first message. No Avro, no Confluent Schema Registry — just plain JSON (ADR-0005, citing YAGNI: a single producer and a single consumer per event don't need a schema registry's contract-evolution machinery).

### The five events that flow through Kafka

| Event | Topic | Emitted by | Consumed by |
|---|---|---|---|
| `OrderPlaced` | `ordering.events` | `ordering` (at checkout) | `payment` |
| `PaymentSucceeded` | `payment.events` | `payment` (after PaymentSimulator says success) | `ordering` |
| `PaymentFailed` | `payment.events` | `payment` (after PaymentSimulator says decline/insufficient) | `ordering` |
| `OrderPaid` | `ordering.events` | `ordering` (when it consumes `PaymentSucceeded`) | `catalog` |
| `OrderCancelled` | `ordering.events` | `ordering` (when it consumes `PaymentFailed`) | `catalog` |

Notice **catalog never listens to `payment.events`**. That's ADR-0009 — "each context publishes events about its own concepts only." Catalog doesn't know about cards or declines; it only knows about *orders being paid or cancelled*. If payments were swapped out tomorrow (Stripe replacing the simulator), catalog code wouldn't change. This is the **orthogonality** principle from Pragmatic Programmer.

### Producer side — the transactional outbox

There is **zero** code in this project that calls `kafkaTemplate.send(...)` directly. Read that again. The pattern most tutorials use — "do your DB write, then `kafkaTemplate.send(message)`" — does not appear anywhere.

Instead, when a module wants to "publish an event," it does:

```java
events.publishEvent(new OrderPlaced(...));
```

That `events` is Spring's `ApplicationEventPublisher`. Spring Modulith intercepts the call, **writes the event into a Postgres table** (`catalog.event_publication`) **in the same transaction** as whatever DB writes happened around it. A separate Modulith background task then drains that table to Kafka.

What this buys you: **if the checkout transaction fails for any reason, the event row is gone too.** If the transaction commits, the event is *guaranteed* to be published eventually, even if Kafka is down at the moment of commit (the outbox row stays until Kafka is reachable again).

The relevant config line in `application.yml`:

```yaml
spring:
  modulith:
    events:
      externalization:
        enabled: true   # ← turns the outbox→Kafka bridge on
```

Visual: `vault/projects/shopsphere/diagrams/transactional-outbox.excalidraw`.

### Consumer side — three `@KafkaListener` classes

There are exactly **three** consumer classes. Each one is a tiny file (~60–100 lines) and follows the same pattern.

#### 1. `payment/PaymentOrderingConsumer.java`

```java
@KafkaListener(topics = "ordering.events", groupId = "payment.orderplaced")
@Transactional
public void onOrderingEvent(ConsumerRecord<String, String> record) {
    // 1. parse JSON, only care about OrderPlaced
    // 2. processedEvents.markProcessed(...) → if duplicate, skip
    // 3. simulator.process(cardNumber, total) → SUCCEEDED or FAILED
    // 4. publish PaymentSucceeded or PaymentFailed via ApplicationEventPublisher
    //    (which goes through the outbox to payment.events)
}
```

This is where the **PaymentSimulator** runs. The simulator is in `payment/PaymentSimulator.java`:

```java
"4242 4242 4242 4242"  →  SUCCEEDED
"4000 0000 0000 9995"  →  FAILED(INSUFFICIENT_FUNDS)
anything else          →  FAILED(DECLINED)
```

#### 2. `ordering/PaymentEventsConsumer.java`

```java
@KafkaListener(topics = "payment.events", groupId = "ordering.payment-events")
@Transactional
public void onPaymentEvent(ConsumerRecord<String, String> record) {
    // 1. parse JSON
    // 2. processedEvents.markProcessed(...) → if duplicate, skip
    // 3. load the Order
    // 4. order.transitionTo(PAID)   OR   order.transitionTo(CANCELLED)
    // 5. publish OrderPaid or OrderCancelled (→ outbox → ordering.events)
}
```

The `order.transitionTo()` call routes through `OrderStateMachine.assertTransition()` which enforces "only `PENDING_PAYMENT → PAID` or `→ CANCELLED` is legal; everything else throws."

#### 3. `catalog/CatalogOrderingConsumer.java`

```java
@KafkaListener(topics = "ordering.events", groupId = "catalog.ordering-terminal-events")
@Transactional
public void onOrderingEvent(ConsumerRecord<String, String> record) {
    // 1. parse JSON, only care about OrderPaid or OrderCancelled
    // 2. processedEvents.markProcessed(...) → if duplicate, skip
    // 3. if OrderPaid:      catalog.confirm(orderId)
    //    if OrderCancelled: catalog.release(orderId)
}
```

This is the consumer that actually moves stock from `HELD` to `CONFIRMED` (qty leaves the system) or from `HELD` to `RELEASED` (qty returns to `availableQty`).

### Subtle thing: notice the dual subscription on `ordering.events`

Two consumers (`payment` and `catalog`) subscribe to the same topic but with **different `groupId`s**:

- `payment.orderplaced` → only cares about `OrderPlaced`, ignores the rest
- `catalog.ordering-terminal-events` → only cares about `OrderPaid` and `OrderCancelled`

In Kafka, each `groupId` gets its own copy of every message. So **the same `OrderPlaced` event is delivered to *both* groupIds**, and each consumer's first job is to look at `eventType` and decide "is this one of mine?" That's how a single topic can carry multiple event types and still let consumers filter for the subset they care about.

### Kafka health (`KafkaHealthIndicator.java`)

Spring Boot ships a Postgres health indicator (`db` component on `/actuator/health`) but **no Kafka indicator out of the box**. So this project adds one — `KafkaHealthIndicator`, a small file at `com.shopsphere.KafkaHealthIndicator`. It opens an `AdminClient` with a 2-second timeout, asks for the cluster description, and reports `UP` with node count (or `DOWN` with the exception). Result: `/actuator/health` is honest about Kafka's state, not just Postgres.

### Summary — full Kafka map of the codebase

```
   PRODUCERS (via Modulith outbox)
   ─────────────────────────────────────────────────────────────
   ordering/CheckoutService          → publishes OrderPlaced
   ordering/PaymentEventsConsumer    → publishes OrderPaid, OrderCancelled
   payment/PaymentOrderingConsumer   → publishes PaymentSucceeded, PaymentFailed

   CONSUMERS (@KafkaListener)
   ─────────────────────────────────────────────────────────────
   payment/PaymentOrderingConsumer       ← ordering.events  (only OrderPlaced)
   ordering/PaymentEventsConsumer        ← payment.events
   catalog/CatalogOrderingConsumer       ← ordering.events  (only OrderPaid/Cancelled)

   INFRASTRUCTURE
   ─────────────────────────────────────────────────────────────
   KafkaHealthIndicator                  → /actuator/health "kafka" component
   common/ProcessedEvents                → per-consumer dedupe gate
   application.yml: spring.modulith.events.externalization.enabled: true
   docker-compose.yml: confluentinc/cp-kafka:7.6.1, KRaft mode (no Zookeeper)
```

---

## 8. The transactional outbox + per-consumer dedupe

These two patterns together are what make the event-driven flow *reliable*. Most tutorials skip both. Worth a section of their own.

### The "dual-write problem" — the bug we're avoiding

Imagine the naive code:

```java
@Transactional
public void placeOrder(...) {
    orderRepo.save(order);                          // DB write
    kafkaTemplate.send("ordering.events", event);   // Kafka publish
}
```

Sequence of events if the JVM crashes between line 2 and the transaction commit:
- DB rolls back (the `Order` row disappears)
- The Kafka message **was already published** — consumers see an `OrderPlaced` for an order that no longer exists.

Sequence if the JVM crashes between the commit and the `kafkaTemplate.send`:
- DB commits (the `Order` exists)
- Kafka message is **never sent** — consumers never learn the order was placed.

Either way you've corrupted reality. This bug class is called **the dual-write problem**.

### The outbox fix

The outbox is dead simple:

1. In your transaction, write the event into a **DB table** (not directly to Kafka).
2. Commit.
3. A separate process polls the table and publishes any unsent rows to Kafka. Once Kafka acknowledges, it marks the row sent.

Now if the JVM crashes mid-flight, the row is either committed (and will eventually be published) or rolled back (and won't be). There's exactly one source of truth.

Spring Modulith does all of this for you. The table is `event_publication` (Modulith owns it). The poller is a Modulith background task. You never see either of them; you just call `events.publishEvent(...)` and the rest is automatic.

### The other half — at-least-once delivery and consumer dedupe

Outbox solves "don't lose a message." But Kafka's delivery guarantee is **at-least-once**: a consumer might be given the same message twice (if it acknowledges late, or if the broker times out an ack). So every consumer must handle "I already processed this; don't do it again."

ShopSphere's solution: each module has a `processed_events(consumer_id, event_id)` table in its own schema. Every consumer, before doing its real work, calls:

```java
if (!processedEvents.markProcessed(CONSUMER_ID, eventId)) {
    log.info("Duplicate event {} — skipping", eventId);
    return;
}
// do the real work
```

`markProcessed` is in `common/ProcessedEvents.java`. It does a SQL `INSERT` — if the row already exists, the `INSERT` fails with `DuplicateKeyException`, which is caught and turned into `return false`. Because the consumer's whole method is `@Transactional`, the insert and the side effect commit together (or roll back together).

The combination — outbox on the producer side + processed_events on the consumer side — turns Kafka's at-least-once into **effectively-once**. The events are published exactly when they should be (outbox guarantee), and consumed exactly once (dedupe guarantee).

> If you ever interview about event-driven systems, this is the answer to "how do you handle duplicate messages?" Don't say "we make consumers idempotent" — *say what you mean*: "a per-consumer `processed_events` table whose insert lives in the same transaction as the side effect."

Visual: `vault/projects/shopsphere/diagrams/transactional-outbox.excalidraw`.

---

## 9. Walking through a single checkout, end to end

Now we tie it all together. This is the flow when a customer hits `POST /api/v1/orders` with `cardNumber=4242424242424242`.

The visual is `vault/projects/shopsphere/diagrams/checkout-sequence.excalidraw`. Open it alongside this text.

### Phase A — the synchronous part (~50ms, in one DB transaction)

The customer's HTTP request lands in `OrderController.place()`, which calls `CheckoutService.checkout()`. Everything inside `checkout()` runs in one `@Transactional` block:

1. **Load the Customer's Cart.** If empty, throw `EmptyCartException` → 400.
2. **Reserve stock.** Call `catalog.reserve(orderId, items)` — this does `SELECT FOR UPDATE` on the relevant product rows, decrements `availableQty`, inserts `StockReservation` rows in status `HELD`. If any line can't be reserved (qty too high), the whole thing aborts → 409.
3. **Build the `Order`.** Look up each cart line's product price right now (from `ProductPriceLookup`), build *snapshotted* `OrderLineItem`s — these store name + unit price + qty *at the moment of checkout*, so the order's history doesn't change if a product's price changes tomorrow.
4. **Save the `Order`** with status `PENDING_PAYMENT`.
5. **Clear the Cart.** (ADR says: cart is NOT restored on failed payment. Checkout is a one-way door.)
6. **Publish the `OrderPlaced` event.** This is the magic line — it's not a Kafka send, it's `events.publishEvent(...)` which Modulith intercepts and writes to the outbox table *in the same transaction*.
7. **Commit.** Either everything above happened, or none of it did. There is no in-between state where the order exists but stock isn't held, or stock is held but no order exists.
8. **HTTP response: 202 Accepted** with `{orderId, status: PENDING_PAYMENT}`. The customer sees this within ~50 ms. They poll later (or watch a WebSocket — that's post-MVP) to see the terminal state.

### Phase B — the asynchronous part (~500–1500 ms total)

Now the customer is gone; the work continues in the background:

9. **Modulith outbox publisher** picks up the new `OrderPlaced` row from `event_publication`, sends it to Kafka topic `ordering.events`, marks it published.
10. **`PaymentOrderingConsumer`** (in the payment module) gets the message. It's wrapped in a `@Transactional` method that:
    a. Inserts `(payment.orderplaced, eventId)` into `payment.processed_events` — if it already exists, return.
    b. Runs `PaymentSimulator.process(cardNumber, total)`. For card `4242…`, the result is `Succeeded(8499 INR)`.
    c. Publishes `PaymentSucceeded` via `events.publishEvent(...)` → outbox row in `payment.event_publication`.
    d. Increments `payments_total{outcome=succeeded}` Micrometer counter.
    e. Commits. Both the dedupe row and the new outbox row commit together.
11. **Modulith outbox publisher** (payment side) sends the `PaymentSucceeded` to `payment.events`.
12. **`PaymentEventsConsumer`** (in the ordering module) receives it:
    a. Insert into `ordering.processed_events` (dedupe).
    b. Load the `Order`.
    c. Call `order.transitionTo(PAID)`, which goes through `OrderStateMachine.assertTransition(PENDING_PAYMENT, PAID)` → legal.
    d. Publish `OrderPaid` → outbox.
    e. Commit.
13. **Modulith outbox publisher** sends `OrderPaid` to `ordering.events`.
14. **`CatalogOrderingConsumer`** receives `OrderPaid`:
    a. Insert into `catalog.processed_events` (dedupe).
    b. Call `catalog.confirm(orderId)` — flips the matching `StockReservation`s from `HELD → CONFIRMED`. (Note: `availableQty` was already decremented in step 2 — confirming just makes the decrement final; the qty leaves the system permanently.)
    c. Commit.

When the customer polls `GET /api/v1/orders/{id}`, the order shows `status: PAID`. Total elapsed: usually 1–2 seconds.

### The failure branch (card `4000…0002`)

Same flow until step 10. The simulator returns `Failed(DECLINED)`. From then on:

- Step 10b: `PaymentSimulator` returns `Failed(DECLINED)`.
- Step 10c: publish `PaymentFailed{reason=DECLINED}`.
- Step 12c: `order.transitionTo(CANCELLED)`.
- Step 12d: publish `OrderCancelled{reason=DECLINED}`.
- Step 14b: `catalog.release(orderId)` — reservations flip `HELD → RELEASED`, qty returns to `availableQty`.

Stock is restored, the order is terminal, the cart was already cleared and stays empty.

### Why each "wait, why is it like that?" question has an answer

| Question | Answer | Where it's written down |
|---|---|---|
| Why isn't `Catalog.reserve` async like the others? | Two carts racing for the last unit must be linearizable — a saga with eventual consistency would be a race. | ADR-0004 |
| Why does Catalog listen to `OrderPaid` and not to `PaymentSucceeded`? | Catalog speaks order-language, not payment-language. Decouples Catalog from "payment provider" details. | ADR-0009 |
| Why is the cart cleared even on failed checkout? | Defining policy — failed checkout doesn't restore the cart, but creates no `Order` either (insufficient stock aborts before step 4). | ADR-0008 + `CONTEXT.md` |
| Why does the order snapshot line items? | So a future price change in Catalog doesn't rewrite history on a past order. | `CONTEXT.md` → "Order" |
| Why not write directly to Kafka? | Dual-write problem. | §8 above, ADR-0003 |
| Why is there a `processed_events` table for each consumer? | Kafka delivers at-least-once; we want effectively-once. | §8 above |

---

## 10. Phases and tracer bullets — why this order

### The tracer-bullet idea (Pragmatic Programmer)

When the Pragmatic Programmer authors talk about a "tracer bullet," the analogy is military: instead of computing where to aim before firing, you fire one round that glows so you can *see* where it lands, then adjust. In software, a tracer bullet is the smallest possible vertical slice — a feature thin enough that it touches every layer (UI, business logic, data, deployment) but does almost nothing — and you ship it end-to-end, *running*. Then every later feature adds to that working system instead of building a tower of "almost done" pieces.

The opposite is **horizontal slicing**: "let me build the whole data layer first, then the whole business layer, then the whole UI." That looks orderly but has a fatal property — you have *nothing running* until the very end, and the integration mistakes are discovered when it's too late to recover cheaply.

### The 9 phases as tracer bullets

Each phase is *the next thinnest end-to-end slice*. The order is intentional:

| # | Phase | Why HERE in the sequence |
|---|---|---|
| 1 | **Walking skeleton** — `GET /api/v1/products` only | The first round. One endpoint, one Postgres table, one Flyway migration, one Testcontainers integration test, CI green. Proves the cake is alive end-to-end before adding any flavor. |
| 2 | **Spring Modulith — define 4 module boundaries** | Establish the boundary-enforcement infrastructure *before* there are many modules to police. Cheap when there's one module, expensive when there are four already entangled. |
| 3 | **Identity — register, login, JWT-protect** | Auth must exist before anything customer-scoped (cart, orders) is built — otherwise you'd build the cart endpoints with no notion of "whose cart" and refactor them later. |
| 4 | **Refresh tokens + logout + reuse detection** | An *increment* on auth — adds the long-lived half once the short-lived half (JWT) is proven to work. |
| 5 | **Cart aggregate + Money** | First customer-data feature. Introduces the `Money` VO now because the next feature (Order) will need to do arithmetic on prices, and `Money` is the way. |
| 6 | **StockReservation aggregate** — the verbs, no checkout yet | Designs the inventory model in isolation. `reserve / confirm / release` can be tested without an Order existing — get them right, then plug them in. |
| 7 | **Checkout + transactional outbox** | First time a single business operation crosses two modules (Ordering → Catalog) and writes a domain event. This is where the **outbox** is introduced — the smallest possible event flow (publish only; no consumer yet). |
| 8 | **Payment Simulator + event loop** | Now the loop closes. Three Kafka consumers come online at once because they all depend on the outbox being in place. The order finally reaches a terminal state. |
| 9 | **Observability** — JSON logs, Actuator, Micrometer | Polish layer. Could've been Phase 1, but adding it last means each preceding phase chose what to instrument from a position of "we now know what matters." |

Notice the property: **at every phase boundary, the app is runnable, tested, and shippable.** You could stop at Phase 5 and you'd have a working "browse + cart" app. You could stop at Phase 7 and you'd have orders that get stuck in `PENDING_PAYMENT` forever — still legitimate, still tested. There is no "half-done refactor" state in the history.

### What makes this hard to do well

The temptation when learning a new stack is to read 10 tutorials and try to *implement all the ideas at once*. Tracer bullets force you to **defer** ideas until they're needed. The clearest example: there is no Phase 1 use of Spring Modulith. The modulith verifier is added in Phase 2, *after* there's actual code to police. Adding it first would mean configuring a tool with nothing to enforce on, then forgetting it exists by the time it matters.

---

## 11. How testing was done (and why no mocks for domain code)

### Three layers of tests

1. **Unit tests** — no Spring context. Plain JUnit + AssertJ. These test pure logic: `Money` arithmetic, `OrderStateMachine.isLegal`, `PaymentSimulator.process`, `PasswordHasher` round-trips. Fast (milliseconds per test).
2. **Integration tests** — full Spring context + **real Postgres + real Kafka** via Testcontainers. These test "the cake from REST controller to DB and back." A Testcontainers test spins up actual `postgres:16` and `confluentinc/cp-kafka:7.6.1` Docker containers, runs Flyway migrations, executes the test, and tears them down. Slow (seconds per test) but truthful — there's no H2 fakery, no embedded Kafka, no mock of `JdbcTemplate`.
3. **Modulith structure test** — one JUnit test that calls `ApplicationModules.of(MainApp.class).verify()`. This **fails the build** if any module imports another module's internal classes. It's the automated enforcement of bounded contexts.

### Why no mocks for domain code?

The phases doc spells it out: "no H2, no embedded Kafka, no mocks for domain modules." The reason: **mocks let bugs hide in the gap between "what the real thing does" and "what the mock pretends it does."** A test that mocks `OrderRepository` and asserts `verify(orderRepo).save(any())` passes if you accidentally save the wrong order. A test that uses a real Postgres + a real `OrderRepository` reads the row back and checks the row is what you expected — and catches the bug.

This is why the integration tests are slow but valuable. They're also why **Docker has to be running** for `mvn verify` to work — Testcontainers needs it.

### The CI gate

GitHub Actions runs `mvn verify` on every PR to `dev`. Branch protection blocks the merge if it fails. The modulith test is part of `mvn verify`, so a forbidden cross-module import will fail CI just like a unit-test failure does.

---

## 12. Observability that's actually there

Phase 9 added the "day-one observability" layer. It's intentionally minimal — no Prometheus, no Grafana, no log aggregation — but everything you need to know "what is this app doing right now":

### Structured JSON logs (Logback + logstash-encoder)

Every log line is a single-line JSON object with `timestamp`, `level`, `logger`, `message`, `threadName`, plus whatever was in MDC at the time. `CheckoutService` puts `orderId` and `customerId` into MDC around the order-placed log line, so that line looks like:

```json
{"@timestamp":"2026-05-28T10:31:14.123Z","level":"INFO","logger":"com.shopsphere.ordering.CheckoutService","message":"Order placed with 1 line(s), total 8499.0 INR","orderId":"7c...","customerId":"4d..."}
```

A single `grep` for `"orderId":"7c..."` traces that order through every module that touched it.

### Spring Actuator endpoints

Exposed under `/actuator/*`:

| Endpoint | What you see |
|---|---|
| `/actuator/health` | Composite of `db` (auto) + `kafka` (custom, see §7) — DOWN if either is dead |
| `/actuator/info` | Git SHA + build timestamp |
| `/actuator/metrics` | List of all available metrics |
| `/actuator/metrics/orders_placed_total` | Counter with dimension `outcome=placed/empty_cart/insufficient_stock` |
| `/actuator/metrics/payments_total` | Counter with dimension `outcome=succeeded/declined/insufficient_funds` |
| `/actuator/metrics/reservations_total` | Counter with dimension `status=HELD/CONFIRMED/RELEASED` |
| `/actuator/metrics/checkout_latency_seconds` | Histogram (count, total time, percentile buckets) |
| `/actuator/modulith` | Module diagram + dependencies, exposed by Spring Modulith |

### Micrometer counters — note WHERE they're incremented

This is a subtle thing the project gets right. The `payments_total` counter is incremented inside `PaymentOrderingConsumer.process()`, **after** the `processedEvents.markProcessed` dedupe gate. That means if Kafka redelivers the same `OrderPlaced` event a second time, the dedupe gate returns false → consumer skips → **counter does not increment again**. The metric reflects real payment attempts, not delivery duplicates. Tutorials usually get this wrong.

---

## 13. How to explain this to someone else (a teaching script)

If a friend says "show me your Spring Boot project," here's a 10-minute script that lands all the important points.

### Opener (1 minute)

> "This is ShopSphere — small ecommerce backend. The interesting thing isn't the features, it's the architecture: it's a *modulith*, not microservices. One Spring Boot process, but with four hard internal boundaries enforced by a tool called Spring Modulith. Inside, the four contexts are catalog, identity, ordering, payment. They talk to each other through Kafka events, not direct method calls — even though they're in the same JVM."

### Show the layout (2 minutes)

Open `docs/CONTEXT.md`. Show the ubiquitous-language glossary at the top — every domain term defined once, with the words to avoid.

> "Every name in that file appears as a Java class and a database column. That's Domain-Driven Design's *ubiquitous language* — and the discipline is that when code and CONTEXT.md disagree, one of them is wrong, you don't just live with it."

Open `docs/adr/` and click ADR-0001.

> "Every architectural choice has an ADR. Each one names which book the idea came from. Microservices were rejected here for two reasons — Ousterhout's strategic-programming argument, and the Pragmatic Programmer's reversibility argument."

### Show the modulith verifier (1 minute)

Open the test class that calls `ApplicationModules.verify()`.

> "This one test fails the build if any module imports another module's internals. So 'don't couple your bounded contexts' isn't a hope, it's a CI check."

### Walk through a checkout (4 minutes) — *the showpiece*

Open `docs/qa-walkthrough.md` and run the §10 happy-path manually. While it's executing:

> "POST /api/v1/orders returns 202 *immediately* — that's intentional. The order's terminal status is reached asynchronously over Kafka. The synchronous part — order created, stock held, outbox row written — is one Postgres transaction. The rest happens through three Kafka consumers."

Open `vault/projects/shopsphere/diagrams/checkout-sequence.excalidraw`.

> "OrderPlaced goes to ordering.events. Payment consumes it, runs a simulator, publishes PaymentSucceeded to payment.events. Ordering consumes that, moves the order to PAID, publishes OrderPaid. Catalog consumes OrderPaid, confirms the reservation. Three hops, three modules, all idempotent because of a per-consumer dedupe table."

### Show the outbox (2 minutes) — *the bit most tutorials skip*

> "Tutorials usually do `orderRepo.save(order); kafkaTemplate.send(event)` — two writes, dual-write problem. If the JVM dies between them, either the order exists without an event or vice versa. We use the **transactional outbox**: the event row is written into Postgres in the same transaction as the order. A separate Modulith task drains it to Kafka. Either both commit or neither does. Read ADR-0003 for the rationale."

Open `vault/projects/shopsphere/diagrams/transactional-outbox.excalidraw`.

### Land the close (30 seconds)

> "Total: one JVM, four bounded contexts, named-and-justified decisions, real Postgres + real Kafka in the test suite, structured JSON logs, Micrometer counters that respect dedupe. The point isn't the e-commerce — it's that every choice has a reason on disk."

---

## 14. What's NOT here yet (honest gaps)

These are real and worth noting — partly because you might add them later, partly because if you tell someone "this is production-ready," you'll be wrong. They're roughly ordered by how cheap they'd be to fix.

| Gap | Severity | What's needed |
|---|---|---|
| **App is not containerized** | Low | A `Dockerfile` for the Spring Boot app, then add it to `docker-compose.yml`. Currently the app runs on the host JVM via `mvn spring-boot:run` — fine for dev, but no parity with how a real deploy would look. |
| **No Prometheus + Grafana hooked up** | Low | Micrometer counters exist, but nobody's scraping them. Add a Prometheus container to compose, point it at `/actuator/prometheus` (need to add the `micrometer-registry-prometheus` dep), add a Grafana dashboard. The session notes flag this as a natural next step. |
| **No Kafka UI for hands-on QA** | Low | The session notes flag `kafbat/kafka-ui` as the obvious addition — lets you see topics and messages during QA. |
| **No card tokenization** | Medium | The raw `cardNumber` is in the `OrderPlaced` event payload (passed through to the simulator). For a real payment provider you'd tokenize *before* writing the event, and never log the PAN. ADR-0006 acknowledges this with "tokenise before production." |
| **JWT secret in env var** | Medium | Fine for dev, but production needs a secrets manager (Vault, AWS Secrets Manager). The current `JWT_SECRET` is in `application.yml` with a dev fallback. |
| **No rate limiting** | Medium | A modulith can survive without it longer than a public-facing microservice, but eventually you want a per-IP rate limit on `/auth/login` to slow down credential-stuffing. Could be a `Bucket4j` filter, or sit behind an edge proxy. |
| **No real warehouse fulfillment** | Medium | ADR-0008 deliberately drops the `FULFILLED` order state because there's no actual warehouse. When real shipping exists, this becomes a sub-state machine (`AWAITING_SHIPMENT → SHIPPED → DELIVERED`). |
| **No AWS S3 for product images** | Medium | Original `BOOTSTRAP-PLAN.md` had S3 + LocalStack in scope; it never made it into the MVP. README's roadmap mentions it. |
| **No admin UI / admin API** | Medium | There's no way to add a product. Products are seeded by Flyway and that's it. A real shop needs CRUD on `Product`. |
| **No frontend** | Medium | Backend only. Currently consumed via Swagger / curl. |
| **Schema validation only — no breaking-change check** | Low | The Modulith test catches package-level coupling. It doesn't catch "I changed the JSON shape of `OrderPlaced` and broke the payment consumer." A schema registry (Avro/Protobuf with Confluent) would; ADR-0005 calls this YAGNI for now and says when to revisit. |
| **Event payload includes raw `cardNumber`** | Security | Tied to "no tokenization." For a real system this is a PCI-DSS issue. |
| **No correlation IDs across requests** | Low | Logs have `orderId`/`customerId` in MDC, but no request-scoped `traceId` that follows an HTTP call into its downstream Kafka consumers. A small `TraceFilter` + MDC propagation in `@KafkaListener` would fix it. |
| **No saga compensation for partial failures** | Low | Right now if `Catalog.confirm()` fails *after* the order is `PAID`, you have a paid order with stale `HELD` stock. Not a real risk today (everything is in one DB) but would be if the modules were extracted. |

None of these blocks the project from being a strong learning artifact. They're the difference between "shipped MVP" (where it is) and "production-grade" (which it doesn't claim to be).

---

## 15. Further reading + glossary

### The docs in this repo, in roughly the order to read them

1. **`README.md`** (this repo) — top-level summary, endpoint map, status.
2. **`docs/CONTEXT.md`** — ubiquitous language. Read it once cover to cover; you'll come back often.
3. **`docs/adr/0001…0009`** — short, one per decision. Each is a 1-paragraph "what + why + what would change our mind."
4. **`docs/qa-walkthrough.md`** — the guided tour for running the app and exercising every feature.
5. **`docs/local-dev.md`** — the quick-reference runbook.

### The vault docs that complement these

1. **`vault/projects/shopsphere/BOOTSTRAP-PLAN.md`** — the meta-plan: how the whole project was scaffolded, including the skill pipeline used.
2. **`vault/projects/shopsphere/plans/shopsphere-phases.md`** — full text of the 9 phases with acceptance criteria.
3. **`vault/projects/shopsphere/prds/PRD-001-shopsphere-mvp.md`** — the original product spec.
4. **`vault/projects/shopsphere/diagrams/*.excalidraw`** — the visual half. 11 diagrams, including the 6 C4 levels, the docker-compose topology, the checkout sequence, the transactional outbox, the state machines, and the module dependencies.
5. **`vault/projects/shopsphere/sessions/`** — one note per work session, narrating *how* each phase was actually built (mistakes, decisions, what was learned). The 10 most recent sessions are a real-time engineering journal.

### Five books to actually read (in order of cost/benefit)

1. **The Pragmatic Programmer** (Hunt & Thomas) — the cheapest to read and the most immediately applicable. Tracer bullets, orthogonality, reversibility, broken windows. Concepts on every page.
2. **Domain-Driven Design** (Evans) — *the* book on bounded contexts. Heavy, but you only need the first 4 chapters + the chapter on bounded contexts to use 80% of it.
3. **A Philosophy of Software Design** (Ousterhout) — short, sharp, opinionated. "Deep modules" and "strategic vs tactical" alone will reshape your interfaces.
4. **Patterns of Enterprise Application Architecture** (Fowler) — the source of the Repository, Service Layer, Unit of Work, Money, and outbox patterns. Reference book, not a cover-to-cover read.
5. **Extreme Programming Explained** (Beck) — short. TDD, small releases, YAGNI. Older than the others but the practices are timeless.

### Quick glossary of acronyms

- **ADR** — Architecture Decision Record. A short note that says "we decided X for reasons Y, and we'd change our mind if Z."
- **DDD** — Domain-Driven Design (the book and the practice).
- **DTO** — Data Transfer Object. A flat record-shape used at the boundary (REST request/response, Kafka message), separate from the domain class that lives inside.
- **JWT** — JSON Web Token. Self-contained signed token used as an access credential.
- **MDC** — Mapped Diagnostic Context. A per-thread map of fields that get attached to every log line — used here for `orderId`, `customerId`, etc.
- **MVP** — Minimum Viable Product. The smallest shippable thing.
- **PoEAA** — *Patterns of Enterprise Application Architecture* (Fowler's book).
- **APoSD** — *A Philosophy of Software Design* (Ousterhout's book).
- **VO** — Value Object. An immutable, identity-less type whose equality is structural (e.g. `Money`).
- **YAGNI** — "You Aren't Gonna Need It." Don't build it until you do.

---

## Final note

If you read this whole document and now want to show someone what you've learned, the fastest test is: **open `vault/projects/shopsphere/diagrams/checkout-sequence.excalidraw`, point at any arrow, and say what guarantees that arrow makes and what would break if you removed it.** When you can do that for all of them, you've earned the right to point at this codebase and say "I understand it."

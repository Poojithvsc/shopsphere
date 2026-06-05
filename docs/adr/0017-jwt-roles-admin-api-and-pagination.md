---
status: accepted
date: 2026-06-05
cites: DDD, PoEAA, APoSD, XP, PragProg
---

# 0017 — JWT roles, an admin API, and a pagination envelope

Phase 17 adds authorization (not just authentication), an operator-only product API, and pagination on the public product list. Three changes, one theme: the API grows a privileged surface and a way to page through data, without disturbing the shopper-facing contract.

## Roles live in the token, authorities are derived

The access token gains a `roles` claim — a JSON array of role names, defaulting to `["USER"]`. The auth filter maps each name to a Spring `ROLE_<name>` authority, and `@EnableMethodSecurity` lets endpoints gate on `hasRole('ADMIN')`. **PragProg / PoEAA** — putting roles *in the signed token* keeps authorization stateless: a guarded request needs no database read to know what the caller may do, matching the stateless-session design already chosen for auth. The honest cost is recorded below (revocation latency).

Roles are stored on `identity.users` as a **denormalised comma-separated column** (`'USER'` or `'USER,ADMIN'`), not a `user_roles` join table. **XP YAGNI / APoSD** — the role set is fixed and tiny, there is no role-management UI, and nothing in the system queries "all users with role X." A join table would add a repository, a mapping, and a join to serve a need that does not exist; the column is the simplest thing that fully works. The normalized table is the obvious deepening *if* roles ever become dynamic or independently queryable, and migrating to it is a contained change. **DDD** — authorization is an Identity concern, so the role data lives in the Identity schema next to the user it describes.

A single **admin is seeded by Flyway `V14`** (`admin@shopsphere.local`, roles `USER,ADMIN`) with a bcrypt hash of a *dev-only* password documented in the migration. It is an operator account, not a shopper; the customer row exists only to satisfy the `users.customer_id` FK. The seed is `ON CONFLICT DO NOTHING` so re-running migrations is safe, and the password must be rotated before any non-local deployment.

## The admin API is a separate, guarded controller

`POST/PUT/DELETE /api/v1/admin/products` live in their own `AdminProductController` under an `/admin` path prefix, with a class-level `@PreAuthorize("hasRole('ADMIN')")`. **APoSD** — keeping the privileged operations in a distinct controller with a single guard at the top makes "what requires admin" obvious at a glance, rather than scattering annotations across the read controller. An authenticated non-admin gets **403** (method security throws `AccessDeniedException`); an anonymous caller gets **401** from the security chain before method security is reached — the two failure modes are deliberately distinct and both tested. The read endpoints (`GET /products`, `GET /products/{id}`) are unchanged and remain available to any authenticated user.

> **Deferred (depends on Phase 16):** the issue also calls for the `POST /api/v1/admin/products/{id}/image` upload endpoint to gain the same guard. That endpoint does not exist yet — Phase 16 (S3 images) is not built — so the guard travels with it when Phase 16 ships. Recorded here so the gap is intentional, not forgotten.

## Pagination: a stable envelope, clamped not rejected

`GET /api/v1/products` takes optional `?page=&size=` via Spring Data `Pageable`. With no params a caller gets the **first page of 20 sorted by name** — behaviourally backward-compatible with the MVP default. The response shape, however, changes from a bare JSON array to a **`PagedResponse` envelope** (`content` + a `page` block of `number/size/totalElements/totalPages`). This is a deliberate, documented contract change: the only consumers are our own QA and tests, and pagination metadata has to live *somewhere*. The envelope is defined as an explicit record rather than serialising Spring's `Page`/`PageImpl` (which Boot 3.3 deprecates serialising directly), so the JSON contract is ours and stable across Spring upgrades. **PoEAA** — this is the Remote Façade returning a DTO we control, not leaking a framework type onto the wire.

An oversized `?size=` is **clamped to 100** (`spring.data.web.pageable.max-page-size`) rather than rejected with 400. **PragProg / robustness** — clamping is the more forgiving contract: a client asking for too much gets the maximum the server will give instead of an error it must special-case, and the server is still protected from unbounded page sizes. The default cap (20) and the maximum (100) are configuration, not code.

## Consequences

Authorization is now expressible per-endpoint and the catalog has an operator surface, with no change to the shopper-facing read or checkout flows (`mvn verify` green, 105 tests). Two honest limits:

- **Token-carried roles mean revocation lag.** Because authority lives in the signed access token, a role change (granting or revoking ADMIN) only takes effect when the access token next expires — at most the 15-minute access-token TTL. For this project that window is acceptable; a system needing instant revocation would consult a store on each request or shorten the TTL. **Deferred, recorded, not designed around.**
- **The admin password is a seeded dev secret.** Fine for local and CI; it must be rotated (or the seed disabled) before any real deployment, exactly as the `V14` comment states.

Both are the kind of deliberate, written-down limitation this project prefers over silent gaps — the same posture as ADR-0014's "grows unbounded" note (since closed) and ADR-0015's deferred Stripe adapter.

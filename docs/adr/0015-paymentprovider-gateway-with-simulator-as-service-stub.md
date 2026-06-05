---
status: accepted
date: 2026-06-05
cites: PoEAA, XP, APoSD, PragProg, DDD
---

# 0015 — A `PaymentProvider` gateway, with the simulator as the Service Stub (live Stripe deferred)

Phase 15 was planned as "wire a real Stripe Test-Mode provider behind a config toggle." On building it, the abstraction turned out to be the whole value and the live adapter turned out to be speculative weight the project does not yet need. This ADR records what shipped — a `PaymentProvider` gateway with the existing simulator behind it — and why the concrete Stripe adapter was **deliberately deferred** rather than built.

## What shipped

The Payment context now exposes a `PaymentProvider` port: `charge(paymentMethodToken, amount) → ChargeOutcome`, where `ChargeOutcome` is `Succeeded(amount)` or `Failed(reason)`. The `OrderPlaced` consumer depends only on that interface; it no longer resolves tokens or knows which backend decides the charge. The sole implementation, `SimulatedProvider`, resolves the token to its redacted last-four (the PAN was tokenized away in Phase 14, so the last-four is all it can see) and delegates to the existing `PaymentSimulator`, mapping its outcome to a `ChargeOutcome`. No event shape changed, no Catalog code changed, and the full suite stayed green — the refactor is invisible to every caller.

## Why the boundary, and why not the adapter

**PoEAA — Gateway + Service Stub.** The pattern this phase is really about is Fowler's *Gateway*: an interface that encapsulates access to an external system, paired with a *Service Stub* that stands in for that system during development and test. `PaymentProvider` is the gateway; `SimulatedProvider` is the Service Stub. Fowler's explicit guidance is that you build and test the entire system against the stub, and only the live gateway implementation depends on the real service being reachable. The valuable half — the seam plus a deterministic stand-in — is exactly what shipped. A live `StripeProvider` would be the other half, needed only when the system actually has to talk to Stripe.

**XP — YAGNI.** A real payment processor for a learning e-commerce modulith is the textbook speculative feature: it earns its keep only at the moment a real charge must clear, which is not a goal of this project. ShopSphere runs and demonstrates the complete order→payment→fulfilment flow in `simulator` mode, fully offline, with no account and no credential. Building the adapter now would be building for a need that has not arrived.

**APoSD — keep complexity that isn't earning its keep out.** A live Stripe adapter drags in the Stripe SDK, network failure modes, retry/timeout handling, sandbox flakiness, and a managed secret — real, recurring complexity behind the deep `PaymentProvider` interface. Ousterhout's test is whether that complexity buys enough; here it buys a capability the project will essentially never exercise. The gateway interface, by contrast, is nearly free and pulls the *option* of that complexity down out of sight until it is wanted.

**PragProg — reversibility and orthogonality.** The seam is the insurance. Because every caller already depends on `PaymentProvider`, adding a `StripeProvider` later is a new class plus a config toggle and changes no existing code — the decision to defer forecloses nothing. This is the same orthogonality ADR-0009 banked on ("if we replace the Payment Simulator with a real provider tomorrow, Catalog code does not need to change"): that promise is now concrete at the provider boundary too.

**DDD — a clean port on the bounded context.** Payment already owns its vocabulary and exposes it outward (ADR-0009); `PaymentProvider` is the port through which "actually charge an instrument" is expressed. Whether the adapter behind the port is a simulator or Stripe is irrelevant to the model's integrity — the boundary is what matters, and it is now explicit.

## Consequences

The project keeps its defining property: it works end-to-end with **zero external dependency**, and `shopsphere.payment.provider`-style toggles and Stripe credentials are simply absent rather than defaulted-and-unused. No config key was added, because a toggle with a single valid value is itself speculative (YAGNI) — the interface, not a property, is the extension point.

The honest cost, recorded so it is not mistaken for an oversight: there is **no live payment integration**, and the "I integrated real Stripe" demonstration is not part of this project. Reintroducing it is a bounded, well-understood task — implement `PaymentProvider` against the Stripe Java SDK using a Test-Mode key, select it with a config toggle, and cover it with a network-gated test outside the default build — and the seam shipped here is precisely what makes that reintroduction cheap if the need ever arrives. **PragProg reversibility**: deferred, not foreclosed.

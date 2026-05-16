---
status: accepted
date: 2026-05-16
cites: APoSD, XP
---

# 0008 — Order states: `PENDING_PAYMENT → PAID` + `CANCELLED`; `FULFILLED` deliberately absent

The Order state machine has three states: `PENDING_PAYMENT` (created, awaiting payment outcome), `PAID` (terminal success), `CANCELLED` (terminal failure, only reachable from `PENDING_PAYMENT`). The earlier grill-me draft included a `FULFILLED` state that auto-transitioned from `PAID`; we dropped it because in MVP there is no warehouse, no carrier, no shipping — the transition would do no observable work.

**APoSD**: a state that exists only to "show off the state-machine pattern" is fake complexity. **XP YAGNI**: when real fulfillment lands (pick + pack + ship + deliver) it will deserve a proper sub-state machine (`AWAITING_SHIPMENT → SHIPPED → DELIVERED`) — that future ADR will reintroduce `FULFILLED` as a meaningful state, or replace it entirely.

Documented because a future reader will ask "why no FULFILLED?" — the answer is "deliberate, not missed."

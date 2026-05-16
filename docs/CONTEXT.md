---
project: shopsphere
type: context
status: placeholder
updated: 2026-05-16
source-of-truth: D:\obsidian-vaults\engineering-journal\projects\shopsphere\CONTEXT.md
---

# ShopSphere — CONTEXT (mirror)

> **Mirror of the vault CONTEXT.md.** Source of truth lives at
> `D:\obsidian-vaults\engineering-journal\projects\shopsphere\CONTEXT.md`.
> Update both on every change. CI will eventually diff them.

## Status

Placeholder. Populated by `to-prd` + `grill-with-docs` after Phase 0.

## Guiding principles

| Source | We pull from it |
|---|---|
| **Patterns of Enterprise Application Architecture** (Fowler) | Repository, Unit of Work, Service Layer, DTO, Domain Model, Data Mapper, Identity Map |
| **A Philosophy of Software Design** (Ousterhout) | Deep modules, information hiding, complexity = dependencies + obscurity |
| **Domain-Driven Design** (Evans) | Ubiquitous language, bounded contexts, aggregates, domain events |
| **Extreme Programming Explained** (Beck) | TDD, red-green-refactor, small releases, CI, YAGNI |
| **The Pragmatic Programmer** (Hunt/Thomas) | DRY, orthogonality, tracer bullets, broken windows |

Every ADR must cite at least one source.

## Ubiquitous Language

_TBD — populated during `grill-with-docs`._

## Bounded contexts

_TBD._ Likely from MVP: **Catalog**, **Identity**, **Ordering**, **Payment** (simulated).

## ADRs

See `docs/adr/`.

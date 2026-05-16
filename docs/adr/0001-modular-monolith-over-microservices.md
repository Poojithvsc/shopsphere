---
status: accepted
date: 2026-05-16
cites: APoSD, PragProg
---

# 0001 — Modular monolith over microservices

ShopSphere is a learning project with no real users, no scale pressure, and a solo developer. We chose a single Spring Boot deployable with internal module boundaries instead of separate services per bounded context. Microservices would buy independent deploys and language choice — neither of which we need — at the cost of network calls, distributed transactions, and operational overhead that would drown the actual lessons (DDD, deep modules, TDD). **APoSD's strategic-programming argument** says invest complexity where it pays back; the payback for microservices is zero here. **PragProg reversibility**: extracting a module to its own service later is mechanical; merging four services back into one because we regretted the split is not. We start where we can move fastest and split only when a real constraint forces it.

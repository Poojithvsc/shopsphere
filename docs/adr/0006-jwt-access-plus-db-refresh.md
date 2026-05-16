---
status: accepted
date: 2026-05-16
cites: PragProg
---

# 0006 — Short-lived JWT access tokens + DB-stored rotatable refresh tokens

**Access tokens** are HS256 JWTs with a 15-minute TTL, validated stateless on every request. **Refresh tokens** are opaque random strings stored server-side in a `refresh_tokens` table (hashed at rest), TTL 7 days, single-use (rotate on every refresh call), and revocable on logout or password change. Token signing secret comes from the `JWT_SECRET` env var.

The pure-JWT alternative (long-lived JWT, no DB) is stateless and tempting but cannot revoke a stolen token before it expires. The pure-session alternative (DB lookup on every request) is revocable but doubles request latency. We get the **PragProg orthogonality** win — auth is the only concern that touches the DB on each request indirectly via the refresh path, and request-time auth checks stay zero-DB.

HS256 (symmetric) over RS256 (asymmetric) because MVP has one signer and one verifier, both inside the same process — RSA's "issuer can sign, anyone can verify" pays no rent yet. Documented here so a future "let's use RSA" suggestion has a real reason to point at. Revisit if we ever extract the API gateway from the monolith.

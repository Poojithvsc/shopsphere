---
status: accepted
date: 2026-06-06
cites: XP, PragProg, APoSD
---

# 0020 — HTTPS via Caddy with a self-signed `tls internal` cert

Phase 20 puts TLS in front of the Phase-12 EC2: a Caddy container reverse-proxies `:443` to the app's `:8080`, terminating HTTPS. The whole config is a ~15-line `Caddyfile` and one compose service. Browsers reach `https://<ec2-public-ip>` and get the app behind TLS, after dismissing a self-signed-cert warning that is expected and acceptable for this iteration.

## Self-signed, because Let's Encrypt is impossible here — not because it's easier

ACME / Let's Encrypt issues *publicly trusted* certs, but only after proving control of a **domain**. A Whizlabs lab has an **ephemeral public IP and no domain**, so domain validation can't happen. `tls internal` makes Caddy mint a cert from its own local CA — untrusted by browsers (hence the warning), but real TLS on the wire. **PragProg — be honest about the trade:** this is the *correct* choice given the constraint, not a shortcut; the limitation (browser warning, no public trust) is named, not hidden. The cert carries the instance's IP in its SAN (browsers send no SNI for bare-IP URLs), which is why the site address is the public IP, injected at deploy time.

## Caddy over nginx — the cert lifecycle is hidden behind two words

`tls internal` is the entire TLS story: Caddy generates the CA, issues the leaf, renews it, and serves it. The nginx equivalent is a `openssl req` to generate a self-signed cert, a volume to mount it, `ssl_certificate`/`ssl_certificate_key` directives, and a renewal you own. **APoSD — deep module:** Caddy presents a two-word interface over the whole certificate lifecycle; nginx exposes the mechanism and makes the operator the lifecycle manager. **XP YAGNI:** for a reverse proxy that does TLS + one `reverse_proxy` line, the simpler tool wins.

## Same Caddyfile local and cloud — proven before the lab

The site address comes from `{$CADDY_SITE_ADDRESS:localhost}`, so the committed `Caddyfile` is byte-identical in both places: unset locally → `localhost` (so `curl -k https://localhost` works on a laptop), and set to the instance IP on the EC2. **PragProg tracer bullet:** TLS termination was proven end-to-end locally (`https://localhost/actuator/health` returns the app's JSON through Caddy; `/api/v1/products` returns 401 — routing + auth intact) **before** spending a minute of lab time. The lab run is then confirmation, not discovery. A bare `:443` site address was tried first and rejected: with no host/IP, Caddy has no name to issue a cert for and the handshake fails — the site address must be concrete.

## Consequences

HTTPS fronts the app with a self-signed cert; plain `http://<ip>:8080` stays open in this iteration (the SG allows both) and is documented as such — closing it would force HTTPS but is unnecessary for the lab. `mvn verify` is unaffected (infra only). Honest limits:

- **Browser warning is inherent** to self-signed; this is not production-grade trust.
- **The on-EC2 acceptance** (`https://<ec2-public-ip>` in a browser, QA walkthrough over HTTPS) is a **manual lab step**, batched with Phase 12 in one Whizlabs session — the two phases share the same ephemeral EC2.
- **On graduation to own AWS, this proxy is replaced by ACM + ALB** — a managed, publicly-trusted cert with auto-renewal, terminating at the load balancer. That's the real-world end state; self-signed Caddy is the lab-appropriate stand-in. Phase 20 is the last numbered phase; nothing depends on it (it's HTTPS polish), so it is skippable in-lab if Phase 12 consumes the time box.

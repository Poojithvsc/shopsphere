---
status: accepted
date: 2026-06-05
cites: APoSD, PoEAA, DDD, XP, PragProg
---

# 0016 — Product images on S3, behind a deep module, with LocalStack for dev/cloud parity

Phase 16 lets an admin attach an image to a product and lets shoppers see it. The bytes live in S3; the database stores only a key; reads are served by short-lived presigned URLs from a private bucket. The design goal that shaped everything was operability: **spin the project up locally and point the same binary at real S3 by changing configuration, not code** — the S3 mirror of the Phase-11 RDS seam.

## A deep module over storage

`ProductImageStorage` exposes two operations — `upload(productId, bytes, contentType) → key` and `presignedRead(key, ttl) → URL` — and hides everything else: the S3 SDK, the client, the bucket, the `<productId>.<ext>` key scheme, the content-type allow-list, and how a URL is signed. **APoSD** — this is a deep module in the same family as Phase-14's `PaymentMethods` and Phase-15's `PaymentProvider`: a tiny interface over a substantial concern. Catalog code (the controllers, the `ProductMapper`) never imports an S3 type; swapping the backend touches no caller. **DDD** — images belong to Products, so the module and the `image_key` column live in the Catalog context; no other module learns that S3 exists.

## The dev↔cloud seam, and LocalStack as a high-fidelity Service Stub

The one knob that flips environments is `shopsphere.storage.s3.endpoint`: a LocalStack URL in dev/test, blank in cloud so the SDK resolves real S3. Credentials follow the same shape — explicit keys for LocalStack, otherwise the default AWS provider chain (instance profile). Path-style addressing is forced so the same configuration works against both. **PragProg — configure, don't hardcode**: the bucket, region, endpoint, and credentials are all configuration; the same JAR serves dev, the fully-containerised `full` compose profile, and cloud.

For testing, the choice was a hand-rolled in-memory fake versus a real S3 API. **PoEAA's Service Stub** says test the whole system against a stand-in — and LocalStack is a *high-fidelity* stand-in: it speaks the real S3 protocol, so the same `putObject`/presign code runs in tests and in production, including signature and expiry behaviour. That fidelity caught a real subtlety: LocalStack **skips presigned-URL signature validation by default**, which would have let an expired URL keep working and made the expiry test a no-op. Turning validation on (`S3_SKIP_SIGNATURE_VALIDATION=0`) makes the stub behave like S3 — the expired-URL-returns-403 test is meaningful precisely because the stub now enforces what S3 enforces. A fake would never have surfaced that.

## Private bucket, presigned reads

The bucket is private — public access blocked at every lever, ACLs disabled, SSE-S3 at rest (the authored, deferred Terraform). Clients never address the bucket directly; `ProductMapper` mints a **5-minute presigned read URL** per product on `GET /products` (null when the product has no image). Presigning is a *local* signature computation — no S3 round-trip — so minting one per row while paging a product list stays cheap. **PoEAA** — the private bucket plus a short-lived signed URL is the standard "don't make storage public to serve it" pattern; the URL is a capability, time-boxed.

## What's deliberately deferred or limited

- **Cloud Terraform is authored, not applied** (`terraform/s3/`), exactly like the secrets work (ADR-0013). The dev/test path is fully LocalStack, so the real bucket is only needed on real AWS; `terraform apply` is a future lab step and the app does not depend on it. **XP YAGNI** — no bucket provisioned for a cloud run that isn't a current goal.
- **Presigned-URL host under the fully-containerised `full` profile.** A presigned URL is signed against the client's endpoint, so when the *app itself* runs inside compose (`S3_ENDPOINT=http://localstack:4566`) the minted URLs read `localstack:4566` — resolvable inside the compose network but not from a host browser. The primary dev loop (app on the host, endpoint `localhost:4566`) mints host-openable URLs, and real S3 has no such split. Splitting the client and presigner endpoints would fix the `full`-profile browser case; it is not worth the extra config knob today (**APoSD** — don't add complexity a real need hasn't asked for). Recorded so it is a known limitation, not a surprise.
- **No image processing.** No resizing, thumbnails, or CDN — upload and presigned read only (**XP YAGNI**). The seam makes adding them later a change behind the interface.

## Consequences

Product images work end-to-end with **zero external dependency**: `docker compose up` brings up LocalStack with the bucket created, and `mvn verify` runs the image tests against LocalStack via Testcontainers — no AWS account, no credential. The admin upload endpoint also lands the `hasRole('ADMIN')` guard that ADR-0017 deferred for exactly this endpoint. The reversibility seam (PragProg) means the move to real S3 is a configuration change plus a `terraform apply`, with the IAM policy scoped to `GetObject`/`PutObject` on the one bucket.

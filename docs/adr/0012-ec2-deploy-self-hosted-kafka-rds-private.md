---
status: accepted
date: 2026-06-06
cites: XP, PragProg, PoEAA
---

# 0012 — EC2 + self-hosted Kafka on one box; RDS goes private; Docker Hub over ECR

Phase 11 stood up a *public* RDS reachable from the developer laptop — a deliberate learning step. Phase 12 is the production-posture deploy: one `terraform apply` provisions an **EC2** that runs the app + Kafka via `compose.cloud.yml` (image pulled from Docker Hub) and a **private RDS** whose only ingress is the EC2's security group. This is the first time ShopSphere runs as it would in front of a user, not on a laptop.

## Self-hosted Kafka in compose on the box, not MSK / Confluent Cloud

The EC2 runs Kafka as a compose service beside the app — the *same* `confluentinc/cp-kafka` image and KRaft config as local dev. **XP YAGNI + the Whizlabs-ephemeral constraint:** MSK and Confluent Cloud are managed, durable, multi-AZ brokers — everything an ephemeral 4-hour lab is not. Provisioning MSK would add a VPC/subnet/permission story, minutes of standup, and cost, to back a broker that's deleted at lab end. **PragProg dev/prod parity:** running the identical broker image locally and on the EC2 means the deploy exercises the exact Kafka the tests and dev loop use — no "works locally, breaks on MSK" surprises. The honest cost is recorded below: single-node, no HA, no durability beyond the box.

## RDS flips to private — the network is the security control

`publicly_accessible = false`, and the RDS security group's only ingress is the EC2's security group (not a laptop /32). The acceptance check is a *negative*: `psql -h <rds-endpoint>` from the laptop must **time out**. **PoEAA / PragProg — push the control to the boundary:** the database isn't protected by application logic or a password alone; it's unreachable off the VPC. The app reaches it because the app runs inside the trust boundary (on the EC2 whose SG is allow-listed). **XP incremental design:** Phase 11 used a public RDS to *learn* the RDS + Flyway path with the laptop as client; Phase 12 removes that affordance now that the EC2 is the client. The two postures are different scenarios, so they live as two self-contained Terraform configs (`terraform/rds/` historical, `terraform/ec2/` the deploy) rather than a migrated state — correct because the lab is throwaway and there's no shared state to preserve.

## Docker Hub, not ECR

The image is built locally and pushed to a public Docker Hub repo (`poojithvsc/shopsphere:latest`), pulled by EC2 user-data. **XP simplicity + the lab constraint:** ECR adds an extra Whizlabs permission scope and a registry that dies with the session; a public Docker Hub repo is a one-line `docker push` and a zero-auth pull. Phase 19 (deferred) would automate this push from CI. The push is manual in this phase and documented in the lab runbook.

## Throwaway-lab posture, made explicit

Default VPC (no bespoke network), t3.micro, no backups, no final snapshot, `apply_immediately`. **XP YAGNI:** a 4-hour box does not warrant private subnets + NAT or a backup plan. This is correct *only* because the lab is ephemeral and holds no real data — the same honesty as ADR-0011. The IAM instance profile is minimal (no SSM yet); ADR-0013 already documents what own-AWS would add.

## Fallback: Postgres as a container when RDS is unavailable (`use_rds=false`)

Added live during the 2026-06-06 lab: the Whizlabs **Cloud Sandbox denies RDS entirely** (even `rds:Describe`), while the only RDS-capable lab (the guided "EC2+RDS Terraform" one) is 60-min and attempt-capped. So the module gained a `use_rds` toggle. When `false`, all RDS resources are skipped (`count = 0`) and a `postgres:16` container runs on the EC2 under a `localdb` compose profile; the app reaches it at `DB_HOST=postgres`.

This is **not** the target architecture and it does **not** demonstrate this ADR's headline lesson — the *managed, private DB behind a network boundary*. A co-located container has no separate network to lock down; the "laptop psql must time out" acceptance check is meaningless against it. That check stays **deferred** to RDS-capable AWS (the guided lab after its attempt reset, or own-AWS), exactly as #58 records.

Why keep it in the codebase rather than as a throwaway branch (decided with the books): it is a genuine **two-value seam**, not a dead toggle — `false` ran live, `true` is `terraform validate`-clean and is the own-AWS path. That mirrors the project's existing abstraction-behind-a-seam stance (cf. ADR-0015's payment stub). **XP YAGNI** is noted honestly: once own-AWS is the only target the container path is dead weight and may be removed; until then it earned its place by being the only way the deploy ran at all.

## Consequences

`terraform apply` brings up the whole deploy; `terraform destroy` removes it. `mvn verify` is unaffected (this is infrastructure, no app code). Three honest limits, recorded so they don't surprise later:

- **t3.micro is tight** — app and Kafka are two JVMs in 1 GiB. `instance_class` is a variable; bump to t3.small if the app OOMs on boot.
- **S3 image storage is dormant in the lab** — `compose.cloud.yml` sets `S3_ENDPOINT` blank, so the SDK resolves real S3 but the core QA flow never calls it (no LocalStack on the box). Real-S3 wiring stays deferred to own-AWS (ADR-0016).
- **Kafka is single-node, non-durable** — fine for the walkthrough, not a statement about production topology.
- The acceptance criteria that *prove* this (app reachable, RDS-private timeout, QA over the EC2 endpoint, `terraform destroy`) are **manual lab steps** — they need a live Whizlabs session, like the other cloud phases. On graduation to own AWS the same module runs with a different `terraform.tfvars` and would add private subnets + NAT, ACM/ALB (ADR-0020), and SSM (ADR-0013). Phase 20 adds HTTPS in front of this same EC2.

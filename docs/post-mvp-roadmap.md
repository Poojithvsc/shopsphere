---
project: shopsphere
type: brainstorm
id: ROADMAP-001
status: draft
created: 2026-05-28
intent: feed into /grill-me → /to-prd → /prd-to-plan → /to-issues → /do-work
prior: [[shopsphere-phases]] (MVP, phases 1–9, shipped)
---

# ShopSphere — Post-MVP Roadmap (Phase 10+)

> **What this is.** A *brainstorm*, not yet a plan. The MVP (`shopsphere-phases.md`) shipped 9 tracer-bullet phases — modulith, auth, cart, checkout, event loop, observability. This doc proposes what comes next, with each proposed phase shaped as a tracer-bullet vertical slice. After reviewing, run `/grill-me` against this file to pressure-test the choices, then `/to-prd` to turn the winners into PRD-002.
>
> **Why this exists now.** `BOOTSTRAP-PLAN.md` originally listed RDS Postgres, S3, LocalStack, AWS SDK, and Whizlabs cloud deploys in scope. When the MVP was sliced, those got deferred — "logic-complete first, cloud second." That's a defensible choice but it left the project running only on a dev laptop. This roadmap brings those scopes back as explicit phases.

---

## 0. Where the MVP left us

| What works | What doesn't (yet) |
|---|---|
| 4 bounded contexts in one Spring Boot modulith | App not containerized — runs on host JVM |
| Postgres + Kafka in docker-compose | Both containers are local-only |
| `mvn verify` is the CI gate, real Postgres + Kafka via Testcontainers | No deploy pipeline; nothing runs in the cloud |
| Outbox + per-consumer dedupe (effectively-once) | Card PAN is in event payloads as plaintext |
| JWT + refresh-token rotation with reuse detection | `JWT_SECRET` is an env var, not a secrets-manager value |
| 3 seeded products, no admin CRUD | No admin API, no image uploads, no S3 |
| JSON logs + Actuator + Micrometer counters | No Prometheus, no Grafana, no log aggregator, no Kafka UI |
| 5 events flow through Kafka cleanly | No schema registry, no contract enforcement |
| 9 ADRs document every choice | No runbook for prod incidents |

See `learn-shopsphere.md` §14 for the full honest-gaps list — this roadmap is shaped to close it in a defensible order.

---

## 1. Proposed phases — 10 through 19

Each row is a tracer bullet: one vertical slice, one PR to `dev`, one article. Sized so a phase is 1–3 sessions of focused work. **Dependencies** matter — a phase can't start until its predecessors are merged.

| # | Slice | Why this position | Depends on | Rough size |
|---|---|---|---|---|
| **10** | **Containerize the app** — production-style `Dockerfile`, app as a compose service, `application-docker.yml` profile | Foundation for every cloud step. Without it, deploys are ad-hoc. | — | S |
| **11** | **AWS RDS Postgres + connect from local app** — Terraform for RDS, Spring profile `cloud`, run local app against cloud DB | The "spin up a cloud DB" milestone you asked for. Smallest meaningful cloud touch. | 10 | M |
| **12** | **Deploy app to EC2** — Terraform extends to an EC2 that runs the app container against the cloud RDS; Kafka still local on the EC2 in compose | First time the *whole stack* runs off your laptop. | 10, 11 | M |
| **13** | **Secrets manager** — `JWT_SECRET` + DB password move to AWS SSM Parameter Store (or Secrets Manager); pulled at boot, never in repo | Right after deploy because env-vars-as-secrets is the first thing a code review would flag. | 11 | S |
| **14** | **Redact PAN from events + introduce a tokenizer** — `OrderPlaced.cardNumber` becomes `cardToken`; PaymentSimulator looks the token up via a `PaymentMethod` aggregate inside Payment | Closes the only outright security issue in the MVP. | — (independent of cloud work) | M |
| **15** | **Stripe sandbox provider** — `PaymentProvider` interface; `StripeProvider` implementation that calls the real Stripe test API; `PaymentSimulator` becomes one of two providers, picked by config | ADR-0009 set this up to be clean — payment vocabulary doesn't leak. This is the payoff. | 14 | M |
| **16** | **Product image upload (S3 + LocalStack)** — `POST /api/v1/products/{id}/image`; LocalStack for dev, real S3 for cloud; product DTO carries the URL | Was in `BOOTSTRAP-PLAN` and never built. | 11 (for cloud) | M |
| **17** | **Admin API + roles** — JWT carries `roles: [USER, ADMIN]`; `POST/PUT/DELETE /api/v1/products`; admin-only endpoints | A real shop needs product CRUD. Also forces a security-roles model. | 15 | M |
| **18** | **Observability stack live** — `micrometer-registry-prometheus`; Prometheus, Grafana, Loki, Kafka UI in compose; one starter dashboard | Counters already exist — this turns them into something you can *look at*. | — | M |
| **19** | **CI/CD pipeline to AWS** — GitHub Actions builds the image, pushes to ECR, terraform-applies, smoke-tests | Brings auto-deploy. Final piece of "code-to-prod" loop. | 12, 18 | L |

> **Total**: ~10 phases. You can pick any subset — the "spin up a cloud DB" goal you stated is **Phases 10–12** specifically (with 13 close behind for secrets hygiene).

### Suggested grouping for the next PRD

If `/grill-me` lands well, I'd group these into 3 PRDs rather than one mega-PRD:

| PRD | Phases | Theme |
|---|---|---|
| **PRD-002 — Cloud deploy** | 10, 11, 12, 13, 19 | Get ShopSphere running on AWS with secrets done right. |
| **PRD-003 — Real payments + admin** | 14, 15, 17 | Replace simulator with Stripe sandbox; add admin CRUD. |
| **PRD-004 — Operations** | 16, 18 | S3 images + the live observability stack. |

Smaller PRDs = smaller plans = clearer slices. Each PRD goes through its own `/to-prd → /prd-to-plan → /to-issues → /do-work`.

---

## 2. Phase 10–12 in detail (the "cloud DB" goal)

You linked the Whizlabs lab (RDS + EC2 via Terraform, MySQL). That lab is the right *shape* but **wrong engine** for ShopSphere — we run Postgres, not MySQL. Adapting it cleanly:

### Phase 10 — Containerize the app

#### Acceptance criteria

- [ ] A multi-stage `Dockerfile` in repo root: build stage uses `maven:3.9-eclipse-temurin-21`, runtime stage uses `eclipse-temurin:21-jre`, copies the fat jar.
- [ ] `application-docker.yml` profile: pulls `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `JWT_SECRET` from env.
- [ ] `docker-compose.yml` extended with an `app` service (build context `.`); Postgres+Kafka unchanged; depends-on/healthcheck wired.
- [ ] `docker compose up -d` brings up the *whole* app including the JVM — no `mvn spring-boot:run` required.
- [ ] `mvn verify` still green (existing tests untouched).
- [ ] README updated: "run with `mvn spring-boot:run` (dev) OR `docker compose up -d --build` (production parity)."

#### Article: *"Containerizing a Spring Boot 3 modulith with a minimal multi-stage Dockerfile."*

---

### Phase 11 — AWS RDS Postgres + connect local app to it

The "spin up a cloud DB and use it" milestone. Standalone — no EC2 yet, just the DB.

#### What we build

A Terraform module under `code/terraform/rds/` that provisions:

- A Postgres 16 RDS instance, `db.t4g.micro`, 20 GB GP3
- A security group allowing inbound on **5432** (NOT 3306 — Postgres, not MySQL) from *your local IP* (so it's not world-open)
- An RDS subnet group in the default VPC
- DB name `shopsphere`, master user `shopsphere`, password from a Terraform variable (NOT hard-coded)
- `skip_final_snapshot = true` and `publicly_accessible = true` (lab/learning posture — flagged for Phase 12 to reverse)

Plus a Spring profile (`application-cloud.yml`) that picks up the RDS endpoint from env.

#### Acceptance criteria

- [ ] `cd code/terraform/rds && terraform init && terraform apply` provisions the RDS instance in ~3 minutes.
- [ ] `terraform output rds_endpoint` returns the host:port.
- [ ] From the local machine: `psql -h <endpoint> -U shopsphere -d shopsphere` connects.
- [ ] `DB_HOST=<endpoint> DB_PORT=5432 DB_NAME=shopsphere DB_USER=shopsphere DB_PASSWORD=... SPRING_PROFILES_ACTIVE=cloud mvn spring-boot:run` boots successfully, Flyway runs, app is live on `localhost:8080` talking to *cloud* Postgres.
- [ ] `qa-walkthrough.md` smoke test (register → login → checkout → poll → PAID) passes against the cloud DB.
- [ ] `terraform destroy` cleans up.
- [ ] Article: *"Spinning up RDS Postgres for a Spring Boot app with Terraform — from `terraform apply` to a paid order in 10 minutes."*

#### The adapted Whizlabs steps (Postgres, not MySQL)

Embedded here so you can re-spin without leaving this doc. **Use these instead of the lab's MySQL steps when the time comes.** Key differences flagged inline with `🔁`.

##### Sign in to AWS Management Console

Same as the lab. Region: `us-east-1`. Use the lab's User Name + Password.

##### Open Visual Studio Code, create a working folder

```powershell
cd $env:USERPROFILE\Desktop
mkdir shopsphere-cloud
cd shopsphere-cloud
code .
```

(Or — better — work directly inside `D:\shopsphere-project\code\terraform\rds\` so the Terraform lives with the project.)

##### `variables.tf`

```hcl
variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "us-east-1"
}

variable "access_key" {
  description = "Access key (only used in Whizlabs sandbox; in real AWS use a profile/SSO)"
  type        = string
  sensitive   = true
}

variable "secret_key" {
  description = "Secret key"
  type        = string
  sensitive   = true
}

variable "my_ip_cidr" {
  description = "Your laptop's public IP in CIDR form (e.g. 203.0.113.7/32) — RDS will allow inbound only from here"
  type        = string
}

variable "db_password" {
  description = "Master password for the RDS instance — DO NOT commit terraform.tfvars"
  type        = string
  sensitive   = true
}
```

##### `terraform.tfvars` (gitignored)

```hcl
aws_region  = "us-east-1"
access_key  = "<from Whizlabs lab page>"
secret_key  = "<from Whizlabs lab page>"
my_ip_cidr  = "<run https://checkip.amazonaws.com — append /32>"
db_password = "<a strong random string, 16+ chars>"
```

🔁 Add `terraform.tfvars` to `.gitignore` if it isn't there — the lab doesn't tell you that.

##### `main.tf`

```hcl
terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

provider "aws" {
  region     = var.aws_region
  access_key = var.access_key
  secret_key = var.secret_key
}

# Security group — Postgres on 5432, NOT MySQL on 3306
resource "aws_security_group" "shopsphere_rds_sg" {
  name        = "shopsphere-rds-sg"
  description = "Allow Postgres from my IP only"

  ingress {
    description = "Postgres from my laptop"
    from_port   = 5432                       # 🔁 NOT 3306
    to_port     = 5432                       # 🔁 NOT 3306
    protocol    = "tcp"
    cidr_blocks = [var.my_ip_cidr]           # 🔁 NOT 0.0.0.0/0 — only YOUR IP
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# RDS Postgres
resource "aws_db_instance" "shopsphere" {
  identifier             = "shopsphere"
  engine                 = "postgres"              # 🔁 NOT mysql
  engine_version         = "16.3"                  # 🔁 Postgres 16 (matches docker-compose)
  instance_class         = "db.t4g.micro"          # 🔁 t4g (Graviton, free-tier eligible)
  allocated_storage      = 20
  storage_type           = "gp3"
  db_name                = "shopsphere"
  username               = "shopsphere"            # 🔁 matches docker-compose
  password               = var.db_password
  parameter_group_name   = "default.postgres16"    # 🔁 NOT default.mysql5.7
  port                   = 5432                    # 🔁 NOT 3306
  vpc_security_group_ids = [aws_security_group.shopsphere_rds_sg.id]
  skip_final_snapshot    = true                    # lab posture
  publicly_accessible    = true                    # lab posture — Phase 12 reverses this
  backup_retention_period = 0                      # save cost for learning
}
```

##### `output.tf`

```hcl
output "rds_endpoint" {
  description = "Host:port for the JDBC URL"
  value       = "${aws_db_instance.shopsphere.address}:${aws_db_instance.shopsphere.port}"
}

output "jdbc_url" {
  value = "jdbc:postgresql://${aws_db_instance.shopsphere.address}:${aws_db_instance.shopsphere.port}/${aws_db_instance.shopsphere.db_name}"
}
```

##### Run

```powershell
terraform init
terraform plan      # eyeball the diff
terraform apply     # type 'yes'; wait ~3 min
terraform output    # copy the endpoint
```

##### Connect from local Spring Boot

Create `src/main/resources/application-cloud.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}    # Kafka stays local for Phase 11

shopsphere:
  jwt:
    secret: ${JWT_SECRET}
```

Run:

```powershell
$env:DB_HOST="<rds_endpoint host>"
$env:DB_PORT="5432"
$env:DB_NAME="shopsphere"
$env:DB_USER="shopsphere"
$env:DB_PASSWORD="<the password from terraform.tfvars>"
$env:JWT_SECRET="dev-only-secret-change-me-32-bytes-minimum-aaaa"
$env:SPRING_PROFILES_ACTIVE="cloud"
mvn spring-boot:run
```

Flyway will create the 4 schemas and seed the 3 products in RDS automatically. The QA walkthrough then runs unchanged.

##### Clean up

```powershell
terraform destroy
```

🔁 Whizlabs sandbox kills resources at the end of your session anyway, but `destroy` is the right habit.

---

### Phase 12 — Deploy the app to EC2 (still using RDS from Phase 11)

#### What changes

- Terraform now also provisions an EC2 instance (Amazon Linux 2023, `t3.micro`) with Docker installed via user-data.
- `publicly_accessible = false` on the RDS — the security group now allows the **EC2's security group** instead of "my laptop." DB is no longer reachable from the internet.
- A `compose.cloud.yml` brings up the app + Kafka on the EC2 (Postgres is RDS now).
- Image distribution: Phase 12 publishes the Docker image to **Amazon ECR**; the EC2 pulls from there. (Phase 19 automates this via GitHub Actions.)

#### Acceptance criteria

- [ ] `terraform apply` provisions RDS + EC2 + ECR repo.
- [ ] `docker build` + `docker push` puts the image in ECR.
- [ ] SSH (or SSM Session Manager) into the EC2 → `docker compose -f compose.cloud.yml up -d` runs the app.
- [ ] App reachable at `http://<ec2-public-ip>:8080/swagger-ui.html`.
- [ ] RDS is private (verified by trying `psql` from your laptop — should fail).
- [ ] The QA walkthrough runs end-to-end against the EC2 endpoint.
- [ ] Article: *"From compose-on-laptop to compose-on-EC2 — minimal-overhead Spring Boot deploy on AWS."*

> Why not ECS Fargate or EKS? Both work, both are heavier. EC2-with-docker is the cheapest learning step and parallels the dev workflow exactly. Phase 19 can graduate to ECS if you want; ADR will mark the transition.

---

### Phase 13 — Secrets manager

#### What changes

- `JWT_SECRET` and `DB_PASSWORD` move from env vars / terraform.tfvars into **AWS Systems Manager Parameter Store** (cheapest) or Secrets Manager (rotation built-in).
- Spring config uses `spring-cloud-aws-secrets-manager-config` to fetch them at boot.
- Terraform creates the parameters with KMS encryption.
- The EC2's IAM instance profile gets `ssm:GetParameter` permission, scoped to ShopSphere's parameter prefix.

#### Acceptance criteria

- [ ] No secrets in `.tfvars`, env vars, or Spring YAML for the cloud profile.
- [ ] App reads `JWT_SECRET` and `DB_PASSWORD` at boot via AWS SDK.
- [ ] Rotating the secret in AWS doesn't require redeploying (or: explicitly does — pick one and document).
- [ ] Article: *"Pulling Spring Boot secrets from AWS Parameter Store — IAM, KMS, and zero env vars."*

---

## 3. Phase 14–19 sketches (lighter detail — will get fleshed out in PRDs 003/004)

### Phase 14 — Redact PAN, introduce a tokenizer

- New aggregate inside `payment`: `PaymentMethod(token, lastFour, customerId, vaultRef)`.
- At checkout, Ordering accepts a card number, sends it to `Payment.tokenize(card) → token`, then writes `OrderPlaced` with **only the token** — never the raw PAN.
- `PaymentSimulator` looks the token up to get the card number for its existing branch logic. (In Phase 15 it gets a real Stripe call.)
- Closes the security gap flagged in `learn-shopsphere.md` §14.

### Phase 15 — Real Stripe sandbox provider

- `PaymentProvider` interface; two implementations: `SimulatedProvider` (existing) and `StripeProvider` (calls Stripe Test mode).
- Picked by config: `shopsphere.payment.provider: simulator | stripe`.
- ADR-0009 (each context owns its events) is what makes this clean — Catalog code doesn't change at all.

### Phase 16 — S3 product images

- LocalStack container in dev compose, real S3 in cloud.
- `POST /api/v1/products/{id}/image` (admin only — Phase 17 lands roles first or pairs).
- Image URL added to `ProductDto`.

### Phase 17 — Admin API + roles

- JWT claims grow a `roles: ["USER"|"ADMIN"]` array.
- Spring Security `@PreAuthorize("hasRole('ADMIN')")` on new endpoints.
- `POST/PUT/DELETE /api/v1/admin/products` — first admin CRUD.
- One bootstrapped admin user via a Flyway migration.

### Phase 18 — Live observability stack

- Add `micrometer-registry-prometheus`.
- Compose: Prometheus, Grafana, Loki + promtail, Kafka UI (`kafbat/kafka-ui`).
- One Grafana dashboard with the existing counters (`orders_placed_total`, `payments_total`, `reservations_total`, `checkout_latency_seconds`).
- Promtail tails JSON logs into Loki; Grafana panels query Loki for `orderId`.

### Phase 19 — CI/CD pipeline to AWS

- GitHub Actions workflow: on push to `main`, build image, push to ECR, run `terraform apply`, SSH to EC2 (or SSM run-command) to `docker compose pull && up -d`, run a smoke test (curl `/actuator/health` and the QA happy path).
- Same workflow runs on PR to `dev` but stops at "image built + tests passed" (no deploy on PRs).

---

## 4. What else is missing for industry-standard production

You asked. Honest list. Things NOT on this roadmap that a senior engineer would still flag before calling ShopSphere "production-grade." Sorted by what bites you first.

### Tier 1 — bites you within a week of being public

| Concern | What's missing | Cheapest fix |
|---|---|---|
| **HTTP-level idempotency** | `POST /api/v1/orders` is not idempotent — a customer double-clicking submits two orders. | Accept an `Idempotency-Key` header on `POST /orders`; store key→orderId in `ordering.idempotency_keys`. |
| **Pagination** | `GET /api/v1/products` returns all rows. Fine for 3, broken at 10 000. | Add `?page=&size=` (Spring Data `Pageable`). |
| **Rate limiting on `/auth/login`** | Brute-force protection is zero. | Bucket4j filter or front the API with an edge proxy (Cloudflare / ALB+WAF) with per-IP rate limits. |
| **Connection pool tuning** | HikariCP defaults — fine for one user. With real traffic you want to tune `maximum-pool-size` and add metrics. | Tune to `(cpu_cores * 2) + effective_spindle_count`; export pool metrics to Prometheus. |
| **CORS policy** | Currently no explicit CORS config; first browser-based frontend will trip on this. | Add a `CorsConfigurationSource` bean with the allowed origin list. |

### Tier 2 — bites you within a month

| Concern | What's missing | Cheapest fix |
|---|---|---|
| **Distributed tracing** | Logs have `orderId` in MDC, but a single HTTP request's trace doesn't follow it through 3 Kafka consumers. | Add `micrometer-tracing-bridge-otel` + propagate via Kafka headers; visualize in Grafana Tempo or AWS X-Ray. |
| **Event schema versioning** | ADR-0005 said "JSON is YAGNI for now." It IS, until you change the shape of an event. | Add `eventVersion` to the JSON envelope; consumers tolerate unknown fields (Jackson already does); breaking changes get a v2 event type. |
| **Graceful shutdown** | App killed mid-Kafka-consume = partial work. | `spring.lifecycle.timeout-per-shutdown-phase: 30s` + Kafka listener container shuts down cleanly. Spring Boot does most of this; verify with a test. |
| **DB migration safety** | A bad Flyway migration locks the table. | Read up on `CREATE INDEX CONCURRENTLY`, expanding writes before contracting reads, and the "blue/green schema" pattern. |
| **Backup verification** | RDS makes backups; have you ever *restored* one? | Quarterly "restore the backup into a scratch DB and run smoke tests" runbook. |

### Tier 3 — design + process gaps

| Concern | What's missing | Where to start |
|---|---|---|
| **Multi-environment** | `dev` and `prod` are the same Terraform module. | Split `code/terraform/{dev,prod}/` with shared modules. |
| **Monitoring alarms** | Metrics exist, alerts don't. | CloudWatch Alarms on `availability < 99.9%`, `5xx rate`, RDS CPU, RDS storage. |
| **Audit log** | Admin actions (Phase 17) won't be auditable. | Append-only `audit.events` table; admin operations write a row inside the same TX. |
| **Security scanning in CI** | No SAST, no dep scanning, no Docker image scan. | Trivy + Snyk + GitHub's CodeQL — all free for public repos. |
| **Performance testing** | No load test exists. | k6 or Gatling script driving the QA flow at increasing RPS; record the breakpoint. |
| **Disaster recovery plan** | RDS in one AZ; what if `us-east-1` has an outage? | Multi-AZ RDS (one-line config flip; cost doubles); for region failover, ADR + scripts. |
| **GDPR / data deletion** | No "delete my customer" endpoint. | `DELETE /api/v1/me` that tombstones the Customer, scrubs PII, and emits a `CustomerDeleted` event for cascading. |
| **Runbook** | If Kafka dies at 3 AM, what do you do? | A `docs/runbook.md` keyed by Actuator-health symptoms: "kafka:DOWN → check broker, check listener, last-resort restart compose." |
| **API contract testing** | A change to `OrderResponse` could break a frontend silently. | Pact (consumer-driven contracts) or schemathesis (property-based against OpenAPI). |

### What this list isn't

It's not "do all of these before you can show this project." It's "here's what a senior reviewer would ask about." Picking any 3–5 of these and turning them into Phase 20+ tracer bullets is a fair way to graduate the project from "shipped MVP" to "production-shaped portfolio piece."

---

## 5. Recommended next step

You said you want the usual workflow: **grill-me → to-prd → prd-to-plan → to-issues → do-work**. The right next move:

1. **Read this doc end to end.** Mark which proposed phases you actually care about. Probably Phase 10–13 first (your stated goal: cloud DB).
2. **Run `/grill-me`** and feed it this file. The skill will challenge you on:
   - Which engine (Postgres confirmed) and version (16.x — match dev).
   - How public the RDS should be (Phase 11 = your IP; Phase 12 = private).
   - Kafka in cloud — MSK ($$$, proper) vs self-hosted on EC2 (cheap, learning) vs Confluent Cloud (free tier, external).
   - Where Spring secrets live (env vars Phase 11 → Parameter Store Phase 13 — or jump straight there?).
   - Whether to bundle 10–13 into one PRD or three.
3. **Then `/to-prd`** produces `PRD-002-cloud-deploy.md`.
4. **Then `/prd-to-plan`** produces `plans/PLAN-002-cloud-deploy.md` with the locked phase list.
5. **Then `/to-issues`** opens GitHub issues on `Poojithvsc/shopsphere` for each phase, labeled `ready-for-agent`.
6. **Then `/do-work`** against the first issue (#14? — whatever the next number is).

If you'd rather skip grill-me and go straight to to-prd, that's fine — but my honest take is that grill-me is exactly where the Kafka-in-cloud question gets settled, and that's the choice this roadmap doesn't make for you.

---

## 6. Honest meta-point

You're right to flag that the original `BOOTSTRAP-PLAN.md` included AWS / RDS / S3 and they didn't make it into the MVP. What happened: when `/to-prd` ran, the PRD got scoped to "logic-complete, runs on a dev laptop"; when `/prd-to-plan` ran, that scope produced 9 phases that were all logic, no infrastructure. Each step was internally consistent, but no step said *"and here's what we explicitly deferred and when we'll come back."* That's the gap. This roadmap fixes it by being the deferred backlog, written down, with the same skill pipeline ready to drive it.

For future projects: the lesson is to have a "deferred from MVP" section in the PRD itself, so the deferral is visible and tracked.

---

Related: [[shopsphere-phases]] · [[../prds/PRD-001-shopsphere-mvp]] · [[../BOOTSTRAP-PLAN]] · `learn-shopsphere.md`

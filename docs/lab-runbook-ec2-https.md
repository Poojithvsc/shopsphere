# Lab Runbook — Phase 12 (EC2 deploy) + Phase 20 (HTTPS) in one Whizlabs session

Both phases share the **same ephemeral EC2**, so they run **back-to-back in one 4-hour lab**. Everything below is pre-staged and committed; this sheet is just the live execution. Do it in order; the whole thing fits comfortably in the time box.

> Why one session: the EC2 is destroyed at lab end. "Do Phase 12 now, Phase 20 later" would mean standing up the entire stack twice. See ADR-0012 / ADR-0020.

## 0. Before the lab (on your laptop, no AWS needed)

```powershell
cd D:\shopsphere-project\code
# Build and push the image Docker Hub (Phase 19 would automate this):
docker build -t poojithvsc/shopsphere:latest .
docker login
docker push poojithvsc/shopsphere:latest
```

- [ ] `poojithvsc/shopsphere:latest` is on Docker Hub.

## 0b. Restarting in a *fresh* Whizlabs lab (if a prior lab expired mid-session)

Each lab is a new AWS account, so the local Terraform state from a dead lab points at gone resources. Don't try to `destroy` the old one (its creds are expired) — just start clean:

```powershell
cd D:\shopsphere-project\code\terraform\ec2
del terraform.tfstate, terraform.tfstate.backup   # ignore "not found"; orphaned resources are auto-reaped by the dead lab
```

`terraform.tfvars` is reusable as-is (only update `my_ip_cidr` if your public IP changed). Then continue from §1 with the new lab's creds.

## 1. Start the Whizlabs lab + credentials

1. Launch the Whizlabs AWS sandbox; note the region (assume `us-east-1`).
2. Export the lab credentials in your terminal (or `aws configure`):
   ```powershell
   $env:AWS_ACCESS_KEY_ID="..."; $env:AWS_SECRET_ACCESS_KEY="..."; $env:AWS_SESSION_TOKEN="..."
   aws sts get-caller-identity        # sanity: returns the lab account
   ```

## 2. Configure Terraform

```powershell
cd D:\shopsphere-project\code\terraform\ec2
copy terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars: set my_ip_cidr (curl -s https://checkip.amazonaws.com -> add /32),
#   db_password, jwt_secret (openssl rand -base64 48). Leave enable_https commented for now.
```

> **Whizlabs lab constraints (discovered 2026-06-06 — already baked into `terraform.tfvars`):**
> - `iam:CreateRole` is **denied** → `create_instance_profile = false` (the instance profile is an empty SSM placeholder; the box needs no AWS API access for the QA flow).
> - `ec2:RunInstances` is **explicitly denied for any type except `t2.micro`** → `instance_class = "t2.micro"`.
> - `sts:DecodeAuthorizationMessage` is denied too, so authz-failure messages can't be decoded — diagnose by hypothesis.
> - The lab IAM user is long-lived (no `AWS_SESSION_TOKEN`); export only the access key + secret.

## 3. Phase 12 — apply (HTTP only)

```powershell
terraform init
terraform apply        # type yes; ~5-8 min (RDS is the long pole)
```

Capture outputs: `ec2_public_ip`, `rds_endpoint`, `app_http_url`.

- [ ] **App is up:** open `http://<ec2_public_ip>:8080/swagger-ui.html` in a browser. The app boots **slowly on t2.micro** (1 GiB, two JVMs + swap) — allow ~5–8 min after `apply` finishes before worrying.
- [ ] **If it never answers**, the instance launched without SSH, so read the boot log via the console instead (no SSH/SSM needed):
  ```powershell
  aws ec2 get-console-output --instance-id <i-xxxx> --output text | Select-String -Pattern "swapon|docker|compose|Started|ERROR|Killed|OOM" -Context 0,2
  ```
  Look for the image pull, `docker compose up`, and any `Killed`/`OOM` (memory) or pull errors. The full boot log is also at `/var/log/cloud-init-output.log` if you do have SSH.

Memory fixes already in place (so the above should not recur): a 2 GiB swapfile in user-data, `KAFKA_HEAP_OPTS=-Xmx384m` and app `JAVA_TOOL_OPTIONS=-Xmx384m` in `compose.cloud.yml`. If it still OOMs, try `instance_class = "t2.small"` (only if the lab policy allows it).
- [ ] **RDS is private (the negative test):** from your laptop,
  ```powershell
  psql "host=<rds-host> port=5432 dbname=shopsphere user=shopsphere"   # must TIME OUT
  ```
  A hang/timeout is the pass — the laptop is no longer allow-listed; only the EC2 SG can reach 5432.

## 4. Phase 12 — QA walkthrough over HTTP

Run `docs/qa-walkthrough.md` against the EC2, swapping the base URL:

```powershell
$BASE = "http://<ec2_public_ip>:8080"
```

- [ ] Register → login → add to cart → checkout (`4242…`) → order reaches **PAID**.
- [ ] Declined card (`4000…0002`) → **CANCELLED**, stock released.
- [ ] (Optional) the rest of the walkthrough (refresh-reuse detection, orders list).

If all green, **Phase 12 is done.** Decide: enough time/energy for Phase 20? It's optional polish. If not, skip to §7 (destroy) — Phase 12 still ships.

## 5. Phase 20 — turn on HTTPS

```powershell
terraform apply -var enable_https=true     # re-runs user-data, starts Caddy with the public IP
```

- [ ] Open `https://<ec2_public_ip>` in a browser → after accepting the self-signed-cert warning, the app loads.
- [ ] `curl -k https://<ec2_public_ip>/actuator/health` returns `"status":"UP"`.

## 6. Phase 20 — QA walkthrough over HTTPS

```powershell
$BASE = "https://<ec2_public_ip>"      # note: 443, no port suffix
# add --insecure / -k to curl calls for the self-signed cert
```

- [ ] At least the happy-path checkout passes end-to-end over HTTPS.
- [ ] Plain `http://<ec2_public_ip>:8080` still works (documented as open in ADR-0020).

## 7. Teardown

```powershell
terraform destroy      # type yes; removes EC2 + RDS
```

- [ ] `terraform destroy` completes; nothing left running (the lab will also reap it, but destroy proves the IaC is clean).

## 8. After the lab (back on your laptop)

Tell me the results and I'll:
- tick the lab-only acceptance boxes on #58 and #64,
- finalize the Phase-12 and Phase-20 article drafts (claims now proven),
- ship the release PR(s) `dev → main` closing #58 and #64,
- record the lab outcome in the vault session log.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| App never comes up on :8080 | t2.micro OOM (app + Kafka in 1 GiB) | Mitigated: swap + heap caps are baked in. If still OOM, `instance_class = "t2.small"` (if lab allows). Confirm via `aws ec2 get-console-output`. |
| `terraform apply` AccessDenied | Lab creds expired / wrong scope | Re-export lab creds; `aws sts get-caller-identity` |
| `iam:CreateRole` denied | Whizlabs IAM user can't make roles | `create_instance_profile = false` (already set) |
| `ec2:RunInstances` explicit deny | Whizlabs allows only `t2.micro` | `instance_class = "t2.micro"` (already set) |
| `psql` from laptop *connects* (should time out) | RDS SG still public, or you applied `terraform/rds/` not `terraform/ec2/` | Confirm you're in `terraform/ec2/`; check `aws_security_group.rds` ingress is the EC2 SG |
| HTTPS handshake fails | Caddy didn't get the public IP | Check `/opt/shopsphere/.env` has `PUBLIC_IP=`; `docker logs shopsphere-caddy` |
| Image pull fails on EC2 | Docker Hub repo private / typo | Ensure `poojithvsc/shopsphere:latest` is public; check `docker compose logs` |

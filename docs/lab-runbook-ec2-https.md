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

## 3. Phase 12 — apply (HTTP only)

```powershell
terraform init
terraform apply        # type yes; ~5-8 min (RDS is the long pole)
```

Capture outputs: `ec2_public_ip`, `rds_endpoint`, `app_http_url`.

- [ ] **App is up:** open `http://<ec2_public_ip>:8080/swagger-ui.html` in a browser. (If it's not up after ~3 min, SSH in or check: the app waits on Kafka health; user-data logs are in `/var/log/cloud-init-output.log`.)
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
| App never comes up on :8080 | t3.micro OOM (app + Kafka in 1 GiB) | `terraform apply -var instance_class=t3.small` |
| `terraform apply` AccessDenied | Lab creds expired / wrong scope | Re-export lab creds; `aws sts get-caller-identity` |
| `psql` from laptop *connects* (should time out) | RDS SG still public, or you applied `terraform/rds/` not `terraform/ec2/` | Confirm you're in `terraform/ec2/`; check `aws_security_group.rds` ingress is the EC2 SG |
| HTTPS handshake fails | Caddy didn't get the public IP | Check `/opt/shopsphere/.env` has `PUBLIC_IP=`; `docker logs shopsphere-caddy` |
| Image pull fails on EC2 | Docker Hub repo private / typo | Ensure `poojithvsc/shopsphere:latest` is public; check `docker compose logs` |

# DEPLOYMENT

**Running now (permanent):** the bootstrap stack only — budget, Terraform state bucket, ECR
repository, GitHub OIDC role (`infra/bootstrap`, ~$0.05/month, estimated). **The app stack
(`infra/app`) is not deployed.** Measured AWS spend: see `PROJECT_STATE.md` §12.

Covers the AWS stack (Phase 7, ADR-023; load-test sessions 9.4) and Kubernetes on kind (Phase 8,
ADR-024).

---

## Cost control rules — read before the first `terraform apply`

1. **Create the AWS Budget alarm at $20 before provisioning anything.** Not after.
2. **Path B (the ECS reference stack) is never left running.** The cycle is
   `apply` → measure → capture evidence → `destroy`. The Terraform code and the
   `docs/perf/` reports are the artifacts.
3. **No NAT Gateway.** At ~$0.045/hour plus data processing it would be the largest
   single line item, exceeding all compute. Fargate tasks run in public subnets with
   security groups permitting inbound only from the ALB security group. See ADR-010.
4. Every AWS component must be classified **Required / Useful / Optional / Too
   expensive** before it is added, with an estimated monthly cost.

## Prerequisites

- Terraform ≥ 1.11 (1.16.4 used), AWS CLI v2 signed in to the target account
  (`aws login`, `aws configure sso` or equivalent; no long-lived keys needed), `gh` for the
  one repository variable.
- `aws sts get-caller-identity` shows the account you mean. Everything is `us-east-1`.

## Bootstrap — once per account (`infra/bootstrap`, Milestone 7.2)

Permanent, and nearly free: the cost alarm, the state bucket every other stack uses, the ECR
repository, and the role CI assumes to push to it. Its own state starts local and is then moved
into the bucket it created.

```bash
cd infra/bootstrap
cp terraform.tfvars.example terraform.tfvars      # budget_alert_emails; github_sub_prefix for a fork (gitignored)

# 1. First apply, local state (backend.tf must not exist yet — move it aside on a fresh clone)
mv backend.tf backend.tf.later
terraform init
#    Already have a budget from the console? Adopt it instead of creating a second:
#    set budget_name in terraform.tfvars to its exact name, then
#    terraform import aws_budgets_budget.monthly "<account-id>:<budget name>"
terraform plan -out=bootstrap.tfplan               # READ IT: expect only creations
terraform apply bootstrap.tfplan

# 2. Move this stack's state into the bucket it just made
mv backend.tf.later backend.tf
terraform init -migrate-state -backend-config="bucket=$(terraform output -raw state_bucket)"
terraform plan                                     # must say: No changes
rm -f terraform.tfstate terraform.tfstate.backup   # the empty local file and the old copy

# 3. Let CI push to ECR (the ARN is not a secret — useless without a matching OIDC token)
gh variable set AWS_ECR_PUSH_ROLE_ARN --body "$(terraform output -raw ci_role_arn)"
```

On any later machine: `terraform init -backend-config="bucket=chess-platform-tfstate-<account-id>"`.

**Never destroyed.** The state bucket has `prevent_destroy` (a destroy plan fails); destroying
it would lose the record of what the app stack created. If this account is being retired:
empty the app stack first, then remove `prevent_destroy`, then destroy.

## CI/CD (Phase 6, ADR-022)

Every PR and every push to `main`: `backend` (unit, ArchUnit, Testcontainers), `frontend`
(type-check + build), `image` (build → Trivy → push). Only `main` pushes, to
`ghcr.io/abhinav2777/chess-platform:<full-commit-sha>` and `:main`, and — once the bootstrap
stack exists and `AWS_ECR_PUSH_ROLE_ARN` is set — to ECR as `chess-platform:<full-commit-sha>`
only (immutable tags), authenticated by OIDC. `main` is protected
(`.github/branch-protection.json`; re-apply after changing it:
`gh api -X PUT repos/Abhinav2777/chess-platform/branches/main/protection --input .github/branch-protection.json`).
A fourth job, `terraform`, runs `fmt -check` / `init -backend=false -lockfile=readonly` /
`validate` per stack — no AWS access; plans and applies stay a reviewed human step. Browser checks run nightly (`.github/workflows/e2e.yml`).

A deployment runs the same image three ways (ADR-021): **`migrate` first** (one-off, must exit
0), then roll out `api` and `worker`. Migrations must stay compatible with the version still
running (expand → deploy → contract).

## Deployment paths

| Path | Stack | Lifetime | Est. monthly cost |
|---|---|---|---|
| A — always-on demo | 1× t3.small EC2, Docker Compose | permanent | ~$15 — **not built** (B only, by decision) |
| B — production reference | Terraform: VPC, ALB, ECS Fargate, RDS, ElastiCache, SQS, ECR, Secrets Manager | on demand | ~$60–80 if left running — **built**, applied per session |
| C — Kubernetes | `kind` locally | local | $0 — **built** (the EKS window was not used) |

All figures are **estimates** derived from published us-east-1 on-demand pricing, not
billing observations. Actual costs go in `PROJECT_STATE.md` §12 once observed.

## App stack (`infra/app`) — apply, verify, destroy

Never left running (rule 2). One session is: apply → verify → measure → destroy. First done
2026-10-01 (ADR-023 §7.5): ~12 min to create (RDS ≈ 9 min), ~10 min to destroy.

**Estimated cost while applied** (us-east-1 on-demand): **≈ $0.14/h, ≈ $3.40/day** — ALB ~$0.03/h,
Fargate api 2 × $0.0247/h, worker on Spot ~$0.005/h, 5 public IPv4 × $0.005/h, RDS + Valkey + two
secrets ~$0.03/h. The forecast budget alert catches a forgotten stack within a day or two.

### 1. Prerequisites

- The bootstrap stack exists (above), and the image you want is in ECR: CI pushes every green
  `main` commit as its full SHA. `aws ecr describe-images --repository-name chess-platform`.
- The AWS CLI is on the machine running `apply` — the migrate step calls it.

### 2. Apply

```bash
cd infra/app
cp terraform.tfvars.example terraform.tfvars           # once
echo "allowed_ingress_cidrs = [\"$(curl -s https://checkip.amazonaws.com)/32\"]" > terraform.tfvars
terraform init -backend-config="bucket=chess-platform-tfstate-<account-id>"
SHA=$(git rev-parse origin/main)                       # after `git fetch`; a green main run
terraform plan -var image_tag=$SHA -out=app.tfplan     # READ IT
terraform apply app.tfplan                             # prints app_url at the end
```

Order is enforced by the graph: network and data tier → **migrate task (must exit 0)** → services
→ `apply` returns only when both services are steady and the targets healthy. A failed migration
stops here with nothing rolled out; read `aws logs tail /ecs/chess-platform/migrate --since 30m`,
fix, and apply again (the failed migrate step is tainted and re-runs).

**Deploying a new version** onto a running stack: the same plan/apply with the new SHA — new task
definitions, migrate re-run, rolling update with the circuit breaker (rolls back if new tasks never
get healthy).

### 3. Verify

```bash
aws ecs describe-services --cluster chess-platform --services api worker \
  --query 'services[].[serviceName,runningCount,deployments[0].rolloutState]'
U=$(terraform output -raw app_url)
curl -s -o /dev/null -w "%{http_code}\n" $U/                    # 200
curl -s -o /dev/null -w "%{http_code}\n" $U/actuator/health     # 404: actuator is on 8081, not routed
cd ../../frontend && APP_URL=$U npm run e2e:lobby              # full game flow, "console errors: none"
```

From a non-allowlisted address the ALB does not answer at all — by design (ADR-023).

### Load-test session (9.4)

Two variables, both off by default, add a k6 task inside the VPC and raise the sign-up limits
(every k6 user registers from one address):

```bash
V="-var image_tag=$SHA -var loadgen_enabled=true -var relaxed_auth_rate_limits=true"
terraform apply $V
loadtest/aws-loadtest.sh 50 120 30        # <games> <play seconds> <ramp seconds>, one level at a time
terraform destroy $V                       # the SAME -vars as the apply
```

For N concurrent games, play must outlast the ramp (`play ≥ ramp + measurement window`): with a
long ramp and short play, games finish before the last starts and concurrency is far below N
(optimisation-02 records the run where that happened).

### Operating safely

- **Refresh credentials first** (`aws login`, then `aws sts get-caller-identity`). Sessions that
  expired mid-apply left healthy services *tainted*; `terraform untaint` them rather than letting
  the next apply replace them (TROUBLESHOOTING).
- **Do not interrupt an apply.** A Ctrl+C once crashed Terraform while it saved state: the lock
  stayed held and two running services were missing from state. Recovery is in TROUBLESHOOTING;
  avoiding it is letting the apply finish, then destroying.
- `terraform force-unlock` only after confirming no Terraform process is running.

### 4. Destroy — and prove it

```bash
cd infra/app
terraform destroy -var image_tag=$SHA      # if it ends in RequestExpired: run it again (TROUBLESHOOTING)
terraform state list | grep -v '^data\.'   # must print nothing
```

## Kubernetes on kind (Phase 8, ADR-024)

```bash
k8s/cluster-up.sh                  # one node, ingress-nginx on host port 80, images preloaded (~70 s)
k8s/deploy.sh <image tag>          # migrate Job gates the rollout; then api ×2 + worker (~50 s)
# http://localhost — the SPA, the API and the socket on one origin
kind delete cluster --name chess   # everything, including the in-cluster PostgreSQL (an emptyDir)
```

The image must exist locally (`docker build -f backend/Dockerfile -t chess-platform:<tag> .`,
or pull a CI-built one from ECR and tag it). The HPA (2–4 pods on CPU) scales up on JVM start;
load tests and drills remove it and fix the replicas, so results are attributable.

## Destroy checklist (Phase 7 onward)

Query each service directly. **Not** the Resource Groups Tagging API: after the first destroy it
still listed security groups and tasks that `describe-*` reported as NotFound.

```bash
aws rds describe-db-instances --query 'length(DBInstances)'                          # 0
aws rds describe-db-snapshots --snapshot-type manual --query 'length(DBSnapshots)'   # 0
aws rds describe-db-instance-automated-backups --query 'length(DBInstanceAutomatedBackups)'  # 0
aws elasticache describe-replication-groups --query 'length(ReplicationGroups)'      # 0
aws elbv2 describe-load-balancers --query 'length(LoadBalancers)'                    # 0
aws ecs list-clusters --query 'length(clusterArns)'                                  # 0
aws ec2 describe-addresses --query 'length(Addresses)'                               # 0 (EIPs bill unattached)
aws ec2 describe-network-interfaces --query 'length(NetworkInterfaces)'              # 0
aws ec2 describe-volumes --query 'length(Volumes)'                                   # 0
aws ec2 describe-nat-gateways --filter Name=state,Values=available,pending --query 'length(NatGateways)'  # 0
aws ec2 describe-vpcs --filters Name=is-default,Values=false --query 'length(Vpcs)'  # 0
aws secretsmanager list-secrets --include-planned-deletion --query 'length(SecretList)'  # 0
aws logs describe-log-groups --query 'logGroups[].logGroupName'                      # none from this stack
aws sqs list-queues                                                                  # none
```

Expected to remain: the bootstrap stack (budget, state bucket, ECR — at most 3 images, OIDC role)
and INACTIVE ECS task definition revisions (free; ECS keeps them).

- [ ] EKS cluster deleted, if one was created (Phase 8 — the control plane bills with no pods)
- [ ] AWS Cost Explorer the next day: the session's cost matches the estimate; nothing unexpected


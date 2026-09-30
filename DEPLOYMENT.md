# DEPLOYMENT

**Running now (permanent):** the bootstrap stack only — budget, Terraform state bucket, ECR
repository, GitHub OIDC role (`infra/bootstrap`, ~$0.05/month, estimated). **The app stack
(`infra/app`) is not deployed.** Measured AWS spend: see `PROJECT_STATE.md` §12.

Filled in through Phase 7 (ADR-023).

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
| A — always-on demo | 1× t3.small EC2, Docker Compose | permanent | ~$15 |
| B — production reference | Terraform: VPC, ALB, ECS Fargate, RDS, ElastiCache, SQS, ECR, Secrets Manager | on demand | ~$60–80 if left running |
| C — Kubernetes | `kind` locally; EKS for a 2–3 day window | mostly local | ~$0 / ~$20 per window |

All figures are **estimates** derived from published us-east-1 on-demand pricing, not
billing observations. Actual costs go in `PROJECT_STATE.md` §12 once observed.

## Destroy checklist (Phase 7 onward)

Run after every Path B or EKS session. A forgotten `apply` is the single most likely way
this project costs real money.

- [ ] `terraform destroy` completed without errors
- [ ] EKS cluster deleted (the control plane bills whether or not pods run)
- [ ] RDS instance deleted, **final snapshot skipped or intentionally retained**
- [ ] ElastiCache cluster deleted
- [ ] ALB and target groups gone
- [ ] Elastic IPs released (they bill when unattached)
- [ ] EBS volumes deleted (they survive instance termination)
- [ ] ECR images pruned to the latest 3 tags
- [ ] CloudWatch log groups have a retention policy set (default is *never expire*)
- [ ] AWS Cost Explorer shows no unexpected resources

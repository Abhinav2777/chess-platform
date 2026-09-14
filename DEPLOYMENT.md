# DEPLOYMENT

**Nothing is currently deployed. No AWS resources exist. AWS spend to date: $0.00.**

This document is filled in during Phase 7. It is stubbed now so the structure exists
and so the cost-control rules are written down *before* anything can be provisioned.

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

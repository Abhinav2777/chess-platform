# ADR-010: ECS Fargate as the production path; EKS in a time-boxed proving window

**Status:** Accepted · **Date:** 2026-09-06

## Context

The spec requires both a realistic AWS deployment (Phase 7, 15–20h) and Kubernetes
(Phase 8, 15–20h). Naively that means deploying the system twice and running an EKS
control plane at roughly $73/month (estimate, published us-east-1 pricing) — against a
total project AWS budget under $50 and a portfolio timeline with no slack.

## Decision

Three paths with different lifetimes:

| Path | Stack | Lifetime | Est. cost |
|---|---|---|---|
| **A — always-on demo** | 1× t3.small EC2, Docker Compose, containerised Postgres+Valkey | permanent | ~$15/mo |
| **B — production reference** | Terraform: VPC, ALB, ECS Fargate, RDS, ElastiCache, SQS, ECR, Secrets Manager, CloudWatch | applied on demand, destroyed after | ~$60–80/mo *if left running* |
| **C — Kubernetes** | full manifests on `kind` locally; EKS applied for a 2–3 day proving window | mostly local | ~$0 local / ~$20 for the window |

Path B is **never left running**. Cycle: `terraform apply` → load test → capture
metrics, dashboards, screenshots → `terraform destroy`. The Terraform code, the k6
reports, and a recorded demo are the artifacts. A permanently-billing ECS cluster is not.

**No NAT Gateway.** At ~$0.045/hour plus data processing it is ~$32/month — more than
all compute combined. Fargate tasks sit in public subnets with security groups that
permit inbound only from the ALB security group, and S3 uses a VPC gateway endpoint
(free). RDS and ElastiCache stay in private subnets, reachable only from the task SG.

## Alternatives considered

**EKS for everything.** Rejected on cost and time. The control-plane charge is constant
whether or not anything is running, and doing Phase 7 on EKS means Phase 8 has nothing
left to demonstrate.

**EC2 + Docker Compose only, no orchestrator.** Cheapest and genuinely fine for this
load — but it demonstrates no orchestration, and the graceful-shutdown-of-a-WebSocket-
server problem (the most interesting Kubernetes content here) never arises.

**Leave everything running for recruiters.** Rejected. Recruiters click a demo URL;
Path A gives them one at $15/month. Nobody inspects a live ECS console.

**App Runner / Elastic Beanstalk.** Rejected: they hide the networking, IAM, and
load-balancing decisions that are the point of Phase 7.

## Consequences

- Load-test numbers come from Path B and must be labelled with the exact instance types
  and dates they were measured on.
- Kubernetes work happens mostly on `kind`, which is honest and must be stated as such.
  The EKS window exists to prove the manifests work against a managed control plane,
  with an ALB Ingress Controller and IRSA — the two things `kind` cannot demonstrate.
- Discipline required: an AWS Budget alarm at $20 and a destroy-checklist in
  `DEPLOYMENT.md`. A forgotten `terraform apply` is the single most likely way this
  project costs real money.

## Interview angle

**Q:** "Is your system deployed on AWS?"
**A:** There's a permanently-running demo on a t3.small, and a full Terraform stack —
VPC, ALB, ECS Fargate, RDS, ElastiCache, SQS — that I apply on demand for load testing
and demos, then destroy. That's a deliberate cost decision for a personal project: the
infrastructure code and the measurements are the artifacts, and leaving it running would
cost about $70 a month to prove nothing extra.

**Q:** "Why no NAT Gateway?"
**A:** It was going to be the single largest line item, around $32 a month — more than
all my compute. My Fargate tasks need outbound access for ECR pulls and SQS, but they
don't need to be private, so I put them in public subnets with security groups that only
allow inbound from the ALB's security group, and used a VPC gateway endpoint for S3.
RDS and ElastiCache stay private. In a regulated environment I'd pay for the NAT or use
interface endpoints; for this workload the security group boundary is the real control.

**Q:** "ECS or EKS — which would you choose in production?"
**A:** Depends on what else the org runs. ECS Fargate has far less to operate and
integrates natively with ALB, IAM task roles, and CloudWatch, so for a single
application team it's usually the better trade. EKS wins when you need Kubernetes'
ecosystem — operators, Helm charts, custom controllers, portability across clouds — or
when the org already has Kubernetes expertise and wants one control plane for everything.
For this system, ECS is the right production answer and I used EKS to demonstrate the
orchestration concepts.

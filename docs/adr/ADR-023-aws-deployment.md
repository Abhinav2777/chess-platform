# ADR-023: AWS deployment — one origin, plain HTTP behind an allowlist, create-measure-destroy

**Status:** Accepted for 7.1 (application side); sections for 7.2–7.5 are added as they are
built · **Date:** 2026-09-30
**Builds on:** ADR-010 (AWS shape, no NAT), ADR-021 (one image, three roles), ADR-022 (CI).
**Amends:** the roadmap's Phase 7 done-when ("public HTTPS + WSS").

## Context

Phase 7 deploys Path B — Terraform, VPC, ALB, ECS Fargate, RDS, ElastiCache, SQS, Secrets
Manager, ECR — as a stack that is applied, measured and destroyed, never left running
(DEPLOYMENT.md rule 2). Owner's decisions, 2026-09-30: existing account, `us-east-1`,
**Path B only**, **no domain, HTTP only**.

No domain means no HTTPS on the ALB: ACM will not issue a certificate for
`*.elb.amazonaws.com`, which nobody but AWS controls. The only no-domain HTTPS option,
CloudFront's default certificate, would add a service the roadmap skips and leave the
CloudFront→ALB leg as plain HTTP anyway.

## Decision

### Scope change: HTTP, with ingress closed to an allowlist

The done-when becomes: *`terraform apply` from zero produces a working HTTP + WS deployment
reachable from the allowlisted addresses; `terraform destroy` leaves nothing billable.*
Credentials over plaintext are acceptable only because nobody else can reach it: the ALB's
security group admits `allowed_ingress_cidrs` (the owner's address), not `0.0.0.0/0`.
HTTPS later = a domain, an ACM certificate, a 443 listener, and
`CHESS_AUTH_REFRESH_COOKIE_SECURE` removed.

### One origin: the SPA is baked into the image

A Node stage in the Dockerfile builds the frontend into `classpath:/static`. The ALB serves
page, API and socket from one hostname.

- Without CloudFront, S3 could host the SPA only as an HTTP website on a *different* origin.
  The refresh cookie is `SameSite=Strict`, so a cross-origin page would never get it back, and
  CORS would have to list a hostname that does not exist until `apply`.
- Same-origin needs no CORS and no configured origin: Spring's WebSocket handshake admits
  same-origin requests before consulting `chess.realtime.allowed-origins`
  (`BehindLoadBalancerIntegrationTest.socketOrigin`).
- The frontend picks its URLs from `import.meta.env.DEV`: `localhost:8080` under the dev server
  (cross-origin on purpose, so CORS stays exercised), its own origin in a production build.
- Security opens exactly `GET /`, `/index.html`, `/assets/**` — not `/**`, which would make
  every future endpoint public by default.
- Cost: frontend and backend deploy together. Fine for a single-owner monolith; the moment they
  need separate release cadences, the answer is CloudFront + S3 with a domain.

### The `aws` profile: trust the ALB's X-Forwarded-For, and only the ALB's

Behind the ALB, `getRemoteAddr()` is the ALB. Every per-IP rate limit (login, register) would
be one bucket for all users — ten failed logins anywhere locks everyone out.
`server.forward-headers-strategy=native` makes Tomcat's RemoteIpValve rewrite the address from
`X-Forwarded-For`, **only** for connections from `internal-proxies` — narrowed to the VPC
(`10.0.x.x`) from Tomcat's default of every private range — and reading from the right, so the
entry used is the one the ALB appended. A client-written prefix is never reached. Tested both
ways (trusted proxy: per-client buckets, spoofed prefix ignored; untrusted peer: header
ignored) and mutation-checked (strategy off → the per-client test fails with 429).

### The refresh cookie's `Secure` flag is configuration — defaulting to on

Browsers drop a `Secure` cookie over HTTP: every reload would silently sign the user out.
`chess.auth.refresh-cookie-secure` defaults to `true`; boxed, so a missing value means the safe
one. Set to `false` only by the HTTP deployment's task definition, and the application logs a
WARN at startup whenever it is off. `HttpOnly` and `SameSite=Strict` are unconditional.

### Smaller items found on the way

- **Banner off.** It printed seven non-JSON lines per start into a JSON log stream. One
  non-JSON line remains — the JVM launcher's `Picked up JDK_JAVA_OPTIONS` notice, which cannot
  be disabled. Accepted.
- **Client errors were 500s.** `ApiExceptionHandler`'s `Exception` catch-all also caught Spring
  MVC's own exceptions: malformed JSON, a non-UUID path variable, a wrong method, a missing
  static file — all "Internal error" with an ERROR log. It now extends
  `ResponseEntityExceptionHandler`, which maps each to its status as a `ProblemDetail`; the
  catch-all sees only the unexpected. Pre-existing since Phase 1; surfaced by the first test of
  a missing asset.

## 7.2 — Bootstrap stack (applied 2026-10-01)

Two Terraform stacks, not one. `infra/bootstrap` is permanent and nearly free (budget, state
bucket, ECR, GitHub OIDC role); `infra/app` is applied and destroyed per session. One stack
would force a choice between destroying the state bucket and the registry CI pushes to every
day, or a "destroy" that leaves things behind — which is the failure DEPLOYMENT.md exists to
prevent.

- **Budget adopted, not recreated** (`terraform import`). The first plan after import wanted to
  drop the console budget's filter excluding credits and refunds — with credits counted, spend
  reads ~$0 while real credit burns and the alarm stays quiet. Caught in plan review; the filter
  is now in code. Lesson: an import's first plan is a diff between intent and reality, and every
  "~" in it is a question.
- **State:** S3, versioned, SSE-S3 (a KMS key is $1/month for nothing a single owner needs),
  public access blocked, TLS-only policy, `prevent_destroy`; **`use_lockfile`** instead of a
  DynamoDB table — deprecated for the S3 backend since Terraform 1.11. The stack's own state
  was migrated into the bucket after the first apply. The bucket name (it contains the account
  ID) is given at `init` time, not committed.
- **ECR:** immutable tags (CI pushes the commit SHA only), scan on push, keep the newest 3.
  CI pushes with `provenance: false` so the lifecycle policy never sees attestation manifests
  it could expire from under a kept image.
- **GitHub OIDC, no keys in GitHub:** trust is `StringEquals` on both `aud` and
  `sub = repo:Abhinav2777@113631636/chess-platform@1369658999:ref:refs/heads/main` — not
  `StringLike` on `repo:owner/*`, the common mistake that trusts every repository of the owner.
  The sub is GitHub's **immutable** form (numeric owner and repository IDs; the repository has
  `use_immutable_subject`). The first version trusted the name-only form and the first main run
  was refused; CloudTrail's failed `AssumeRoleWithWebIdentity` event recorded the sub GitHub sent.
  Kept strict rather than loosened: a name-only trust is inherited by any *new* repository that
  later takes the same name; IDs are never reused. Permissions:
  `GetAuthorizationToken` (not scopable) plus push actions on one repository. No thumbprint —
  AWS verifies GitHub's issuer against its own CA store.
- **Verified live, not from the code:** public access block, versioning, encryption, ownership,
  TLS-only policy; anonymous HTTP and HTTPS both 403; the role's trust conditions; ECR
  immutability; `plan -destroy` refused by `prevent_destroy`.

## 7.3 — Network and data (written and planned 2026-10-01; applied with 7.4)

- **VPC 10.0.0.0/16, two AZs, two tiers, no NAT.** Public subnets for the ALB and tasks (tasks
  get public IPs for egress); isolated subnets with *no route out* for RDS and ElastiCache — so
  the data tier's isolation does not depend on a security group being right. Two AZs because the
  ALB and both subnet groups require them, not for availability. The CIDR is coupled to
  `internal-proxies` in `application-aws.yml`; a variable validation enforces it.
- **Security groups by reference, one rule per resource.** ALB ← allowlist:80; api ← ALB:8080;
  worker ← nothing; db ← api/worker:5432; cache ← api/worker:6379; tasks → 443 anywhere (AWS APIs
  via the internet gateway — interface endpoints would cost more than the NAT we avoided). The
  VPC's default group is taken over with no rules. Terraform-created groups start with no egress,
  so the data tier has none.
- **RDS PostgreSQL 16** (the tested major), db.t4g.micro, gp3, encrypted, private, single-AZ.
  Own parameter group: `log_min_duration_statement=250`, `rds.force_ssl=1`,
  `idle_in_transaction_session_timeout=60s`. Logs exported to a log group **Terraform creates
  first** — one RDS creates on its own has no retention and outlives `destroy`. Destroy-by-design:
  no final snapshot, no deletion protection, automated backups deleted with the instance.
- **Passwords never in state.** The master password is RDS-managed (Secrets Manager). The JWT key
  is an *ephemeral* `random_password` written through `secret_string_wo`: in AWS, not in the plan
  or the state file (verified in the plan output). Secrets use `recovery_window_in_days = 0` so the
  next apply can reuse the name.
- **Known limitation — rotation.** RDS rotates the managed master secret every 7 days; tasks read it
  at start. Longer than this stack lives; a long-lived deployment would use a separate app user or a
  driver that re-reads the secret.
- **Valkey 8.2** (the tested major), one cache.t4g.micro node, no replica, no snapshots (ADR-004: not
  a source of truth), encrypted at rest and **TLS in transit, required** — the app enables its client
  TLS by environment in 7.4.
- **SQS** `game-events` + `game-events-dlq` exactly as `SqsQueues` creates them locally
  (maxReceiveCount 3, visibility 30 s), long polling, SSE, and a redrive-allow policy so only
  `game-events` may dead-letter into the DLQ.
- **Estimated cost of this tier while applied:** RDS ~$0.016/h + storage, Valkey ~$0.013/h, two
  secrets — **≈ $0.03/h, ≈ $0.80/day** (us-east-1 on-demand, published prices; not yet measured).

## 7.4 — Compute (written and planned 2026-10-01; applied in 7.5)

- **Actuator moved to port 8081 in the `aws` profile, health details off.** Found while wiring
  the health check: on 8080, `/actuator/health` listed every component, and
  `/actuator/prometheus` was readable by *any signed-in player* (security required only
  "authenticated"). The ALB forwards 8080 only and health-checks 8081 directly; Phase 9's
  scraper will reach 8081 from inside the VPC. Three tests, including one that pins the profile
  file itself — the others run the management server on a random port and would pass anyway.
- **Execution role vs task role.** Execution (ECS starting the task): pull *this* image, write
  *these* log groups, read *these two* secrets — written out instead of the managed
  `AmazonECSTaskExecutionRolePolicy`, which covers every repository and log group. Task role
  (the running app): **none** for api and migrate — they call no AWS API — and two queues for the
  worker. Trust conditioned on `aws:SourceAccount` (confused deputy).
- **Secrets injected by reference** (`valueFrom`, `:password::` picks the JSON field); the task
  definition holds ARNs, not values.
- **Migrate before rollout, enforced by the graph.** `terraform_data.migrate` runs the migrate task
  via `scripts/run-migrate.sh` (run-task, wait, fail unless exit 0) whenever the migrate task
  definition changes — every new image — and both services depend on it. A failed migration stops
  the apply with the previous version serving. Needs the AWS CLI where `apply` runs.
- **Services:** api ×2 on Fargate (the smallest count that proves cross-instance fan-out), worker ×1
  on **Fargate Spot** (interruption-tolerant: outbox in PostgreSQL, SQS redelivery). Circuit breaker
  with rollback; `wait_for_steady_state` so `apply` succeeds only when tasks are healthy; 180 s
  health-check grace for JVM start on 0.5 vCPU. x86 — CI builds amd64; Graviton would need an
  emulated multi-arch build.
- **ALB:** idle timeout 60 s against the client's 25 s PING; `drop_invalid_header_fields`;
  deregistration delay 30 s (default 300 s) to match graceful shutdown — WebSockets cut at the end
  reconnect and resync (ADR-007).
- **JDBC `sslmode=require`:** encrypted, fails rather than falling back to plaintext; does not verify
  the server certificate (that needs the RDS CA bundle in the image). Accepted inside the VPC.
- **Not built:** container health check for the worker (the JRE image has no curl; a hung worker is
  not detected until Phase 9's alarms), ECS Exec, ALB access logs.

**Estimated cost while applied** (us-east-1 on-demand; Fargate from the Pricing API, others
published list prices; not yet measured): ALB ~$0.03/h, api 2 × $0.0247/h, worker on Spot
~$0.005/h, 5 public IPv4 × $0.005/h, data tier ~$0.03/h — **≈ $0.14/h, ≈ $3.40/day.**

## 7.5 — First apply (2026-10-01): what production found

**Attempt 1** (image `d8fbb9b`): 62 resources; RDS 8 m 43 s, ElastiCache 4 m 41 s; the migrate
task **applied all 7 migrations** over TLS in 0.9 s — then exited 1. Services were never created:
the migrate-first ordering held, and nothing ran on a half-done deploy. Two causes:

1. **The migrate role connected to Valkey.** Terraform gave every task
   `CHESS_REALTIME_FANOUT=valkey`, so migrate started a pub/sub listener it has no use for. Now
   api and worker only. A schema migration must not depend on the cache.
2. **One Valkey timeout was doing two jobs.** Lettuce bounds connection initialisation (TCP +
   TLS handshake + HELLO) by the command timeout — 1 s, chosen in Phase 4 so requests fail fast.
   On 0.25 vCPU the first TLS handshake took longer (the same task needed 4 s for its first
   PostgreSQL TLS connection, and ~100 s to start Spring). Error:
   `Connection initialization timed out after 1 second(s)`. **Reproduced locally** with a TCP
   proxy that answers 2 s late — same message, same frame (`RedisHandshakeHandler`). Fix
   (`ValkeyClientConfig`): client timeout = `chess.valkey.connection-init-timeout` (10 s) for the
   handshake; `TimeoutOptions` expires each command after `spring.data.redis.timeout` (1 s).
   Both tests mutation-checked (no TimeoutOptions → commands wait 10 s; init 1 s → handshake fails).

Why local never showed either: compose's Valkey is plaintext, on a fast machine, and always up.
The test suite now carries the slow-handshake shape; TLS itself is still exercised only in AWS.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Buy a domain** (~$3–15/yr) | The better option and the recommended one; declined by the owner. Recorded as the path to HTTPS. |
| **CloudFront default certificate** | HTTPS to the browser, plaintext CloudFront→ALB; adds a service the roadmap skips. |
| **SPA on S3 website hosting** | HTTP-only, cross-origin: breaks the `SameSite=Strict` refresh cookie and brings CORS back. |
| **Read X-Forwarded-For in the controller** | Any client can set it; takes the attacker's value unless every hop is validated — which is exactly what RemoteIpValve does. |
| **Drop `Secure` unconditionally** | Weakens every environment for the sake of one. |
| **ElastiCache Serverless for Valkey** (raised by the owner, 7.3) | Cheaper: 100 MB minimum × $0.084/GB-h = $0.0084/h vs $0.0128/h for cache.t4g.micro; ECPUs ~$0 at our volume (Pricing API, 2026-10-01). Saves ~$0.10/day applied — ~$0.50 over Phases 7–9. But Serverless is cluster-mode: `seek.lua` touches four slots and `pair.lua`/`cancel.lua` derive keys at runtime (ADR-016), so matchmaking would fail with CROSSSLOT in AWS while every single-node Testcontainers test passed. Making it safe — one `{mm}` hash tag for all matchmaking keys, a cluster-mode client, a clustered Valkey in tests — is ~4–6 h. Poor ROI; revisit only if the cache becomes long-lived. |

## Consequences

- The deployment is not publicly reachable; a demo means adding the viewer's address to
  `allowed_ingress_cidrs`.
- `internal-proxies` is coupled to the VPC CIDR (`10.0.0.0/16`); changing one means the other.
- A frontend-only change rebuilds and redeploys the backend image.

## Interview angle

**Q:** "Your rate limit is per IP. What does it see behind a load balancer?"
**A:** The load balancer's address — so at first, one bucket for everyone. I turned on Tomcat's
forwarded-header handling but trust only the VPC's addresses, and it reads X-Forwarded-For from
the right, so the address used is the one the ALB appended, not whatever the client wrote. I
test it both ways and I watched the test fail with the setting off.

**Q:** "Why is your deployment HTTP?"
**A:** No domain — ACM can't issue a certificate for the ALB's hostname. I made it an explicit
decision: the ALB only accepts my own address, the cookie's Secure flag is configuration that
defaults to on and logs a warning when it's off, and HTTPS is a domain, a certificate and one
listener away.

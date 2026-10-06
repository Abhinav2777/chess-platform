# Security review — OWASP API Security Top 10 (2023)

**Date:** 2026-10-06 · **Phase:** 10.1 · **Scope:** the API and WebSocket surface, the SPA's
response headers, and the AWS deployment's IAM and network (Terraform in `infra/`).

**Method:** every claim in `ARCHITECTURE.md` §10 checked against the code; then the running
application probed (local profile, the built SPA served by Spring); then each finding fixed, held by
a test over real HTTP (`SecurityReviewIntegrationTest`, one test per finding, each mutation-checked:
undo the fix and exactly that test fails), and re-probed. Not a penetration test, and nothing here
was done by a third party.

## Findings

| # | Finding | OWASP | Severity | Evidence before | Fixed by | After |
|---|---|---|---|---|---|---|
| 1 | **No limit on request body size.** Spring parses `@RequestBody` before the controller — and its rate limit — runs; Tomcat's `maxPostSize` covers forms only; the ALB has no limit. | API4 | **High** — one unauthenticated request ends an API task | 50 MB body to `/api/auth/login`: read and parsed (0.47 s), then 401. **Four concurrent 100 MB bodies: heap 58 → 2,463 MB.** An AWS task's heap is 512 MB; `ExitOnOutOfMemoryError` would end it. | `RequestBodyLimitFilter`: first in the chain, 16 KB (`chess.http.max-request-body`); declared length over → 413, nothing read; chunked → capped while read | 413 in 2 ms; chunked: 400 in 9 ms |
| 2 | **Deployed signing key could fall back to a public one.** `application.yml` defaults `chess.auth.jwt-secret` to a development key committed to this repository. On AWS the secret comes from Secrets Manager — but a renamed or dropped mapping would have started the service signing tokens anyone can mint. | API2 / API8 | **High if it happened**; latent (not the case in any deployment so far) | Config reading: no guard anywhere | `application-aws.yml` references `${CHESS_AUTH_JWT_SECRET}` with no default: a missing secret fails startup. `AwsSigningKeyConfigTest` pins it. Verified every AWS role (api, worker, migrate) receives the secret (`ecs.tf` `common_secrets`) | — |
| 3 | **REST served any game to any signed-in user** — legal moves included — while WebSocket `SUBSCRIBE` refused non-players. One authorisation rule per transport. | API1 | Medium (game data is not secret; the inconsistency is the defect) | A third user's `GET /api/games/{id}`: 200 | Players-only, the same `NOT_A_PLAYER` as `SUBSCRIBE` and `Game.sideOf` | 422 `NOT_A_PLAYER`; players still 200 |
| 4 | **No Content-Security-Policy**, though `ARCHITECTURE.md` had claimed "strict CSP" since Phase 0. | API8 | Medium (defence in depth against XSS) | Headers: `nosniff`, `X-Frame-Options: DENY`, no CSP | Same-origin CSP (`default-src 'self'`; `img-src` adds `data:` for the inlined pieces and favicon; no `unsafe-*`), `Referrer-Policy: same-origin` | Full browser flow (`e2e:lobby`) green, **0 console errors** — no violations; WebSocket allowed by `connect-src 'self'` (Chromium) |
| 5 | **A password the API accepted crashed registration.** `@Size(max = 72)` counts characters; bcrypt's limit is 72 *bytes*, and the encoder throws past it. | API8 | Low (an error, not a bypass) | 20 emoji (20 chars, 80 bytes): **500** + ERROR log | `@MaxUtf8Bytes(72)` beside `@Size` | 400, per-field `VALIDATION_FAILED` |
| 6 | **A move from a square to itself was a 500**, REST and WebSocket alike: `IllegalArgumentException` reached the catch-all. | API8 | Low | e2→e2: **500** / `INTERNAL` + ERROR log | `MoveIntent` throws `IllegalMoveException` for it — it *is* an illegal move | 422 `ILLEGAL_MOVE` |
| 7 | **WebSocket frame size bounded only by a default nobody chose** (Tomcat's 8 KB text buffer). Correct today. | API4 | Info | 8 KB – 32 MB frames: all closed 1009 | Pinned in `application.yml` (`server.servlet.context-parameters`); test: a 16 KB frame before AUTH closes with 1009 | Unchanged behaviour, now explicit |

Also found and fixed in 9.4, by load rather than review: unvalidated WebSocket payloads (a missing
game id opened a transaction, then an unhandled error — now Bean-Validated before any transaction),
and bcrypt bursts starving every request (bounded hashing pool with 503 load shedding).

## Corrections to `ARCHITECTURE.md` §10 (claims that were not true)

| Claimed | Actual |
|---|---|
| "TLS terminated at ALB; WSS only in production" | The deployment is **HTTP-only** (no domain, ADR-023), restricted to an address allowlist. TLS is used to RDS (forced) and Valkey. |
| "Strict CSP header" | Not sent until 10.1 (finding 4). |
| "SSM Parameter Store (config)" | Not used. Secrets come from Secrets Manager (RDS-managed credentials, the JWT key); configuration from task-definition environment. |
| "CSRF … uses SameSite=Strict + a double-submit token" | **No double-submit token.** The refresh cookie relies on `SameSite=Strict` (+ `HttpOnly`, path-scoped) and the CORS allowlist. Accepted: the cookie is never sent cross-site, so a cross-site page can neither refresh nor read the result; the API itself authenticates by header, which a cross-site page cannot set. Residual: SameSite treats sibling subdomains of one registrable domain as same-site — relevant only if the app ever shares a domain with untrusted subdomains. |
| "HSTS" | Spring Security sends HSTS on HTTPS requests only; this deployment is HTTP, so none is sent. Correct behaviour; becomes effective with HTTPS. |

`ARCHITECTURE.md` §10 is updated to match.

## The ten categories

**API1 — Broken object-level authorisation.** Objects a user can address: games (by ID), their own
profile, their refresh-token family. `/api/users/me` takes no ID — the identity comes from the
token, so there is nothing to tamper with. Moves and resignation resolve the caller's side with
`Game.sideOf`, which refuses non-players. `GET /api/games` lists only the caller's games. `SUBSCRIBE`
re-checks membership on every subscribe. **Finding 3** was the exception. Game IDs are UUIDv7: the
first 48 bits are a timestamp, the remaining 74 random — not enumerable, but authorisation never
relies on that.

**API2 — Broken authentication.** Access tokens: HS256, algorithm pinned in the decoder (no `alg`
confusion), expiry validated, 15-minute TTL (ADR-009). Refresh tokens: opaque, hashed at rest,
rotated on every use, reuse revokes the whole family (ADR-013). Login: per-IP and per-username
token buckets; "no such user" and "wrong password" are identical in message and — via a dummy
hash — in timing. Passwords: bcrypt cost 12, minimum 12 characters, at most 72 bytes (finding 5).
WebSocket: authenticated in the first frame within 5 s, at most 100 unauthenticated sockets per
instance. **Finding 2** was the gap. Noted, not a vulnerability: bcrypt's `matches()` ignores bytes
past 72 (`"a"×73` matches the hash of `"a"×72`, verified); since nothing over 72 bytes can be stored,
a match still requires the entire real password.

**API3 — Broken object-property-level authorisation.** Requests bind to records that declare their
fields — never to entities — so `{"rating": 3000}` on registration is ignored (tested since 1.2).
Responses are separate records: the profile carries id, username, rating and creation time; never
email or the password hash. Game responses carry player IDs and usernames, nothing else of theirs.

**API4 — Unrestricted resource consumption.** Bounded now: request bodies (finding 1), WebSocket
frames (finding 7), page size (`GET /api/games`, clamped to 50), password hashing (bounded pool,
503 when full — 9.4), unauthenticated sockets (100, 5 s), outbound buffer per socket (512 KB, then
closed), rate limits on login, registration, moves (shared by REST and WebSocket) and seeks.
**Not bounded:** a per-IP limit on authenticated reads — accepted at this scale; the next step would
be a limit at the load balancer (AWS WAF rate rule, ~$5/month + per-request — not worth it here).

**API5 — Broken function-level authorisation.** There are no administrative functions. Deny by
default: every path not listed in `SecurityConfig` requires authentication. Actuator: on the
application port only health is open; in AWS and Kubernetes it moves to port 8081, which no
listener, Service or Ingress exposes and which the security group admits only from the ALB, for
health checks.

**API6 — Unrestricted access to sensitive business flows.** Registration: 5 per minute per IP, no
CAPTCHA, no email verification — accepted; mass sign-up is slowed, not prevented. Rating boosting
between one person's accounts is possible and out of scope (it needs behavioural detection).
Matchmaking: seeks rate-limited; one active game per player.

**API7 — Server-side request forgery.** The server never fetches a URL a client supplied. Not
applicable.

**API8 — Security misconfiguration.** Errors: RFC 7807 bodies with no message from unexpected
exceptions and no stack traces (`include-message: never`); an incident ID correlates with the log.
Findings 4, 5, 6 were here. CORS: explicit origins with credentials (no wildcard). The refresh
cookie's `Secure` flag is configuration that defaults on and logs a startup warning when off (the
HTTP deployment). Spring Boot's default user is excluded. TLS required to RDS (`rds.force_ssl`) and
used to Valkey.

**API9 — Improper inventory management.** One API, one version, one deployment at a time; the
surface is documented in `docs/api/` (Postman collection) and `ARCHITECTURE.md` §5. Stacks are
applied for a session and destroyed — nothing old stays reachable.

**API10 — Unsafe consumption of APIs.** The rating consumer trusts its queue, so who can write to it
is the control: only the worker's task role may send to or receive from `game-events`; the API and
migrate tasks have **no AWS permissions at all** (`task_role_arn = null`). The only producer is our
own outbox relay. Duplicates are expected and neutralised (`processed_events`, ADR-008). The chess
library is a third-party dependency behind a port, checked by perft node counts (ADR-002).

## Infrastructure and supply chain (beyond the Top 10)

- **Network:** RDS and Valkey in isolated subnets with no route out. The tasks run in public
  subnets with public IPs — how they reach AWS APIs without a NAT gateway (ADR-010) — and nothing
  reaches them inbound except the ALB: their security groups admit only the ALB's group. Egress
  is HTTPS to anywhere, plus the data tier by security-group reference. ALB ingress only from an
  address allowlist. Trade-off, accepted for cost: a compromised task could send anywhere over 443;
  VPC endpoints (~$7/month each) or a NAT with egress filtering would close that.
- **IAM:** CI deploys through GitHub OIDC (no long-lived keys); per-role task permissions.
- **Secrets:** Secrets Manager; RDS credentials managed by RDS. `*.tfvars`, `*.tfplan` and state
  are gitignored — checked: never committed. (Plan files can contain secret values; stale ones on
  a workstation are worth deleting.)
- **Images and dependencies:** Trivy blocks HIGH/CRITICAL before push; actions pinned by SHA;
  Dependabot; base image OS packages patched on a dated build argument (ADR-021, ADR-022).

## Accepted, recorded

| Risk | Why accepted | Revisit when |
|---|---|---|
| HTTP-only deployment | No domain (owner's decision, ADR-023); allowlisted | A domain is bought: ACM certificate + HTTPS listener; HSTS then applies |
| No CAPTCHA / email verification | Scope; rate limits slow mass sign-up | Public launch |
| No per-IP limit on authenticated reads | Portfolio scale; WAF costs more than it protects here | Public launch |
| Access tokens not revocable for 15 min | ADR-009 | A security requirement demands it |
| SameSite=Strict without a CSRF token | See corrections table | The app shares a registrable domain with untrusted subdomains |

# ADR-013: Refresh token rotation with family-based reuse detection

**Status:** Accepted · **Date:** 2026-09-14
**Refines:** ADR-009 (JWT access + rotating refresh), which specified rotation but not
what rotation is *for*.

## Context

ADR-009 chose short-lived JWTs plus opaque refresh tokens. That leaves an unanswered
question: a refresh token lives 14 days, is stored client-side, and cannot be verified
cryptographically. If it leaks, what limits the damage?

## Decision

**Rotate on every use, retain used tokens, and treat reuse as proof of theft.**

Each token carries a `family_id` inherited by its successors. Refreshing marks the
presented token used and mints a replacement in the same family. Presenting an
already-used token revokes the **entire family**.

## Why reuse detection, not just rotation

Rotation alone shortens the exposure window; it does not detect anything. The detection
comes from what rotation makes *impossible in normal operation*:

```
attacker refreshes with T1 -> succeeds, gets T2, T1 marked used
victim   refreshes with T1 -> T1 already used
```

A legitimate client would have moved on to T2. So a used token reappearing is not an
error condition — it is evidence that two parties hold the same lineage. We cannot tell
which is the thief, so both are logged out.

**The asymmetry is the point.** The victim re-enters a password once. The attacker loses
access permanently, including the token they just minted. Without family revocation the
attacker keeps T2 and detection accomplishes nothing.

This also explains a detail that otherwise looks like a bug: **used tokens are retained,
not deleted.** A deleted token is indistinguishable from one that never existed, and the
theft signal disappears with it. Retention outlives token expiry for the same reason.

## Concurrency

Two requests can carry the same refresh token legitimately — a double-clicked retry, two
tabs. A read-check-write in Java would let both pass `if (!token.isUsed())` and both mint
a pair; one pair belongs to nobody, the other looks like theft.

The claim is therefore a conditional UPDATE:

```sql
UPDATE refresh_tokens SET used_at = :now WHERE id = :id AND used_at IS NULL
```

Exactly one caller sees a row count of 1. Same principle as `uq_users_username` in
registration and `PRIMARY KEY (game_id, ply)` in ADR-005: **the invariant is enforced
where writes are serialised, not where the code is convenient.**

The honest cost: a genuine double-click logs the user out, because the second request is
indistinguishable from reuse. Mitigations exist (a short grace window where the immediate
successor is also accepted) and are **not implemented** — the failure is rare and safe,
and a grace window is a hole an attacker can aim at.

## Storage

Only `SHA-256(token)` is stored. **Not bcrypt** — bcrypt's slowness defends low-entropy
secrets against dictionary attack, and a 256-bit CSPRNG token has no dictionary. Paying
~250 ms per refresh would buy nothing and hand an attacker a CPU-exhaustion lever on a
constantly-called endpoint. The reason to hash at all is unchanged: a database dump must
not yield live sessions.

## Alternatives considered

**Long-lived non-rotating refresh tokens.** Simpler, and what most tutorials do. Rejected:
a leaked token is valid for its full lifetime with no signal that anything happened.

**Stateless refresh (a second JWT).** No database, but no revocation either — the
property refresh tokens exist to provide.

**Sliding sessions in Redis.** Makes Valkey load-bearing for authentication, so a cache
outage becomes a full outage. Rejected on the same grounds as ADR-004.

## Consequences

- A `refresh_tokens` row per login and per refresh. Bounded by an expiry purge; not a
  volume concern at portfolio scale, and it would shard by `user_id` cleanly if it were.
- Reuse revokes a whole family, so a user with a leaked token is logged out of every
  session in that lineage. Correct, and worth stating in the UI.
- `RefreshTokenService` logs reuse at WARN. That log line is the security signal — it
  should eventually be an alert (Phase 9).

## Interview angle

**Q:** "You rotate refresh tokens. What does that actually buy you?"
**A:** On its own, not much — it shortens the window a stolen token is useful. The value
is that it makes theft *detectable*. Every token is single-use, so if an already-used one
comes back, two parties hold the same lineage, which cannot happen legitimately. I can't
tell the thief from the victim, so I revoke the entire family. The victim signs in again;
the attacker is out for good, including the token they'd just minted. Without revoking
the family the attacker keeps their fresh token and the detection is worthless.

**Q:** "What if the user double-clicks and both requests carry the same token?"
**A:** They get logged out, and that's deliberate. The claim is a conditional UPDATE with
`WHERE used_at IS NULL`, so exactly one request wins and the other is treated as reuse.
I could add a short grace window that accepts the immediate successor, but that's a hole
an attacker can aim at, and the failure mode I've chosen is rare and fails safe.

**Q:** "Why SHA-256 for these and bcrypt for passwords?"
**A:** bcrypt is slow on purpose to make dictionary attacks on human-chosen secrets
expensive. A refresh token is 256 bits from a CSPRNG — there's no dictionary, so a fast
hash is just as unbreakable. And bcrypt on the refresh endpoint would be ~250 ms of CPU
on every call, which is a denial-of-service lever, not a security gain.

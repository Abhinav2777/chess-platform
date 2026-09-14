# ADR-009: JWT access tokens + rotating refresh tokens; WebSocket first-message auth

**Status:** Accepted · **Date:** 2026-09-06

## Context

Two problems. (1) Authenticating REST requests across N stateless pods. (2)
Authenticating a WebSocket connection — where the browser API **cannot set arbitrary
headers on the handshake**, so `Authorization: Bearer …` is simply unavailable.

## Decision

**REST:** short-lived JWT (HS256, 15-minute TTL) in the `Authorization` header, plus an
opaque 256-bit refresh token stored hashed in PostgreSQL, rotated on every use, in an
httpOnly `SameSite=Strict` cookie. Passwords: bcrypt cost 12.

**WebSocket:** connection opens unauthenticated; the client's **first message must be
`AUTH {token}`** within 5 seconds or the socket is closed. Unauthenticated sockets are
capped per IP.

## Alternatives considered — REST

**Server-side sessions in Valkey.** Rejected. It makes Valkey load-bearing for
authentication, so a cache outage becomes a total outage. It also means every WebSocket
frame's authorisation check would need a Valkey round-trip.

**Long-lived JWTs, no refresh.** Rejected. A stolen token is valid until expiry with no
revocation. Short access + rotating refresh gives a 15-minute worst case and lets us
detect refresh-token reuse (a reused rotated token means theft → revoke the family).

**RS256 instead of HS256.** Deferred. RS256 matters when a separate service must verify
tokens it cannot sign. We have one service. The migration is trivial if that changes.

## Alternatives considered — WebSocket

**Token in the query string** (`wss://…/ws?token=eyJ…`). Rejected. URLs land in ALB
access logs, proxy logs, and browser history. This is the most common approach and it
leaks credentials into places with weaker retention controls than the token deserves.

**Cookie-based** (rely on the refresh cookie during handshake). Rejected: the browser
sends cookies on cross-origin WebSocket handshakes, which reintroduces CSRF surface on
the socket.

**`Sec-WebSocket-Protocol` header smuggling.** Works — the browser *does* let you set
this one header — but it abuses a field meant for subprotocol negotiation, and some
proxies mangle it.

**Short-lived single-use ticket** fetched over REST, passed in the query string.
Legitimate; the leak is bounded because the ticket is single-use with a ~10s TTL. Kept
as the documented alternative if first-message auth proves awkward.

## Consequences

- No sticky sessions needed: any pod can verify any token locally. This is the same
  property that makes cross-instance fanout work (ARCHITECTURE.md §5.4).
- We must implement the auth timeout and the unauthenticated-connection cap, or the
  pre-auth window becomes a trivial socket-exhaustion DoS.
- Authorisation is re-checked on every `SUBSCRIBE`, not just at connect. A valid token
  does not imply the right to watch a specific game.
- Token revocation is not immediate for access tokens. 15 minutes is the accepted
  window; a `jti` denylist in Valkey is the escape hatch if immediate revocation is ever
  needed.

## Interview angle

**Q:** "How do you authenticate a WebSocket?"
**A:** The browser WebSocket API won't let you set an `Authorization` header on the
handshake, so the usual options are a token in the query string or a cookie. I do
neither. The socket opens unauthenticated and the client's first frame must be an `AUTH`
message with the JWT, within a five-second timer. That keeps the credential out of URLs
and access logs, and avoids reintroducing CSRF via cookies. The cost is a pre-auth
window, so I cap unauthenticated sockets per IP — otherwise it's a socket-exhaustion
vector.

**Q:** "Why JWT rather than sessions?"
**A:** Because any pod has to be able to authenticate a WebSocket connection without a
lookup, and without sticky sessions. A session store would make Valkey load-bearing for
auth — a cache outage would become a full outage — and would put a network round-trip in
front of every frame's authorisation check.

**Q:** "JWTs can't be revoked. Isn't that a problem?"
**A:** It's a bounded one. Access tokens live 15 minutes; refresh tokens are opaque,
stored hashed in Postgres, and rotated on every use, so those are revocable immediately.
Rotation also gives me theft detection: if a refresh token is presented twice, the token
family is compromised and I revoke all of it. If I ever needed instant access-token
revocation I'd add a `jti` denylist in Valkey — but that reintroduces the lookup, so
I'd only do it if the 15-minute window were actually unacceptable.

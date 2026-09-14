# API collection

`chess-platform.postman_collection.json` — import into Postman via **Import → File**.

Regenerated at each phase boundary. It is executable documentation: running it verifies
status codes, error codes, and cookie attributes that the JUnit suite does not assert
because they are HTTP concerns rather than logic.

## Running it

```bash
docker compose -f ops/docker/docker-compose.yml up -d
./gradlew :backend:bootRun --args='--spring.profiles.active=local'
```

Then **Run collection** in Postman. 38 requests, 92 assertions, all green on a working
build. Folders are numbered and must run in order — folder 1 creates the accounts that
3 and 4 use.

Usernames carry a random per-run suffix, so re-running never collides with accounts from
the previous run and no teardown is needed.

## Why the refresh cookie is handled manually

The cookie is set `HttpOnly; Secure; SameSite=Strict`. Postman's cookie jar will not
reliably replay a `Secure` cookie over plain HTTP to localhost, so the auth requests parse
it out of `Set-Cookie` into a collection variable and send it back as an explicit `Cookie`
header. A browser does this transparently; Postman needs the help.

This is a property of the test client, not a weakness in the design — and being unable to
read the cookie from script is precisely the point of `HttpOnly`.

## Worth running by hand

Three folders demonstrate behaviour that is hard to see from the test suite:

- **2 → "Unknown user → identical 401"**. Compare the response *time* with the
  wrong-password request above it. They match because the server hashes a dummy value when
  no user exists — identical messages close the enumeration oracle in the body, but not in
  the clock.
- **2 → "Refresh token REUSE"** followed by **"…and the current token is dead too"**. The
  second is the assertion that matters: revoking only the presented token would leave the
  attacker's freshly minted one working (ADR-013).
- **4 → the two idempotent-move requests**. Same `clientMoveId`, same answer, and
  `expectedPly` is still 0 on the retry yet it succeeds — because the idempotency check
  runs before the stale check. A retry is not a stale client.

## Not covered

WebSocket traffic. Postman's WebSocket support does not fit the first-message auth
handshake well; Phase 2 adds a scripted client under `ops/` instead.

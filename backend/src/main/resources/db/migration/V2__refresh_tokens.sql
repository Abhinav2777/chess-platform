-- V2: refresh tokens with rotation and reuse detection (ADR-009).

CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- SHA-256 hex of the opaque token. The raw value exists only in the client's cookie.
    --
    -- Why SHA-256 here but bcrypt for passwords: bcrypt is deliberately slow to defend
    -- LOW-ENTROPY inputs against dictionary attack. A refresh token is 256 bits from a
    -- CSPRNG — there is no dictionary, and brute force is infeasible regardless of hash
    -- speed. Paying bcrypt's ~250ms on every token refresh would buy nothing and hand an
    -- attacker a CPU-exhaustion lever on an endpoint that runs constantly.
    token_hash  VARCHAR(64) NOT NULL,

    -- Rotation lineage. Every token minted by refreshing an earlier one inherits its
    -- family. Presenting an already-used token means the lineage leaked, so the whole
    -- family is revoked rather than just that token.
    family_id   UUID        NOT NULL,

    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,

    -- Non-null means already rotated. Presenting such a token is the theft signal.
    used_at     TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,

    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user   ON refresh_tokens (user_id);

-- Supports the expiry sweeper as an index scan bounded by expired rows, not a table scan.
CREATE INDEX idx_refresh_tokens_expiry ON refresh_tokens (expires_at)
    WHERE revoked_at IS NULL AND used_at IS NULL;

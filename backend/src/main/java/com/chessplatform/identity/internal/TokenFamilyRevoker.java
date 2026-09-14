package com.chessplatform.identity.internal;

import com.chessplatform.identity.domain.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Revokes a refresh-token family in its own transaction.
 *
 * <p>Reuse detection revokes a family and then throws, to return 401. Under Spring's
 * default rules a RuntimeException rolls back — discarding the revocation. The victim
 * still gets a 401, and the attacker's token quietly keeps working.
 *
 * <p>A separate bean because self-invocation bypasses the transaction proxy. A public
 * method because CGLIB cannot proxy non-public ones and Spring ignores @Transactional
 * on them without complaint. Either mistake silently restores the original bug.
 */
@Service
public class TokenFamilyRevoker {

    private final RefreshTokenRepository tokens;

    public TokenFamilyRevoker(RefreshTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revoke(UUID familyId, Instant now) {
        return tokens.revokeFamily(familyId, now);
    }
}
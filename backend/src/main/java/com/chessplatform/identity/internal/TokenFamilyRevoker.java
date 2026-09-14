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
 * <h2>Why this class exists</h2>
 *
 * <p>Reuse detection revokes a family and then throws, to return 401. Those two actions
 * are in direct conflict under Spring's default transaction semantics: a
 * {@code RuntimeException} triggers rollback, so the revocation is discarded by the very
 * exception that signals it. The victim still gets a 401, and the attacker's token
 * quietly keeps working — the failure is invisible from the response and defeats the
 * entire point of family revocation.
 *
 * <p>{@code REQUIRES_NEW} suspends the caller's transaction and commits this one
 * independently, so the revocation survives regardless of what the caller does
 * afterwards.
 *
 * <h2>Why not simply {@code noRollbackFor}</h2>
 *
 * <p>It would work today and break later. {@code RefreshTokenService.rotate} joins an
 * outer transaction started by {@code AuthenticationService.refresh}; the exception
 * propagates to that boundary, so the outer rollback rules govern the actual commit.
 * Suppressing rollback correctly would mean annotating every layer, and the guarantee
 * would silently evaporate the first time someone wrapped the call in another
 * {@code @Transactional} method. A separate physical transaction cannot be undone by
 * anything up the stack.
 *
 * <h2>Why a separate bean</h2>
 *
 * <p>Spring's transaction handling is proxy-based, so a self-invocation inside
 * {@code RefreshTokenService} would bypass the interceptor entirely and silently run in
 * the caller's transaction — the same bug with no visible cause. The method is public for
 * the same reason: CGLIB cannot proxy non-public methods, and Spring ignores
 * {@code @Transactional} on them without complaint.
 *
 * <h2>Cost</h2>
 *
 * <p>A second pooled connection is held while the caller's transaction is suspended,
 * against a pool of 10. Acceptable because reuse detection is by definition rare — it
 * happens only when a token has actually leaked. It would be the wrong pattern on a hot
 * path.
 */
@Service
public class TokenFamilyRevoker {

    private final RefreshTokenRepository tokens;

    public TokenFamilyRevoker(RefreshTokenRepository tokens) {
        this.tokens = tokens;
    }

    /** @return the number of tokens revoked */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revoke(UUID familyId, Instant now) {
        return tokens.revokeFamily(familyId, now);
    }
}

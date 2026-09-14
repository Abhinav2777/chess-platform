package com.chessplatform.identity.internal;

import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import com.chessplatform.common.id.Uuid7;
import com.chessplatform.identity.domain.RefreshToken;
import com.chessplatform.identity.domain.RefreshTokenRepository;
import com.chessplatform.platform.security.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <h2>Rotation</h2>
 *
 * <p>Every refresh consumes the presented token and mints a replacement. A refresh token
 * is therefore single-use, which bounds how long a stolen one is worth anything: the next
 * time either party refreshes, the other's token becomes invalid.
 *
 * <h2>Reuse detection — the reason rotation is worth the complexity</h2>
 *
 * <p>Rotation alone does not detect theft, it only shortens the window. Reuse detection
 * does. Consider a token stolen from a cookie:
 *
 * <pre>
 *   attacker refreshes with T1  -> succeeds, gets T2. T1 marked used.
 *   victim   refreshes with T1  -> T1 is already used.
 * </pre>
 *
 * <p>That second event cannot happen in normal operation — the legitimate client would
 * have moved on to T2. So an already-used token being presented is proof the lineage
 * leaked. We cannot tell which party is the thief, so we revoke the whole
 * {@code family_id} and force re-authentication. The victim is inconvenienced once; the
 * attacker's access ends. That asymmetry is the right trade for session credentials.
 *
 * <p>This is why tokens carry a family ID at all, and why used tokens are retained rather
 * than deleted — a deleted token is indistinguishable from one that never existed, and
 * the theft signal disappears with it.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** 256 bits. Enough that the token needs no stretching when hashed (see TokenHasher). */
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository tokens;
    private final TokenFamilyRevoker familyRevoker;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository tokens, TokenFamilyRevoker familyRevoker,
                               AuthProperties properties, Clock clock) {
        this.tokens = tokens;
        this.familyRevoker = familyRevoker;
        this.properties = properties;
        this.clock = clock;
    }

    /** Starts a new lineage. Called on password login only. */
    @Transactional
    public IssuedToken issueNewFamily(UUID userId) {
        return issueInFamily(userId, UUID.randomUUID());
    }

    /**
     * Consumes a refresh token and issues its successor.
     *
     * @throws DomainException.Unauthorized if the token is unknown, expired, revoked, or
     *                                      already used (which also revokes its family)
     */
    @Transactional
    public Rotation rotate(String rawToken) {
        Instant now = Instant.now(clock);

        Optional<RefreshToken> found = tokens.findByTokenHash(TokenHasher.sha256Hex(rawToken));
        if (found.isEmpty()) {
            throw invalid();
        }
        RefreshToken token = found.get();

        if (token.isUsed()) {
            // Theft signal. See the class comment.
            //
            // MUST go through TokenFamilyRevoker, not tokens.revokeFamily(...) directly.
            // This method is transactional and is about to throw a RuntimeException,
            // which rolls the transaction back — taking the revocation with it. The
            // caller would still receive a 401 while the attacker's token kept working,
            // so the bug is invisible in the response.
            int revoked = familyRevoker.revoke(token.familyId(), now);
            log.warn("Refresh token reuse detected; revoked family={} tokens={} user={}",
                    token.familyId(), revoked, token.userId());
            throw invalid();
        }
        if (token.isRevoked() || token.isExpiredAt(now)) {
            throw invalid();
        }

        // The real guard. The checks above give precise behaviour in the common case;
        // this decides. A conditional UPDATE means exactly one concurrent caller claims
        // the token even if several passed the checks above simultaneously.
        if (tokens.claimForRotation(token.id(), now) == 0) {
            log.warn("Concurrent rotation lost the race; family={} user={}",
                    token.familyId(), token.userId());
            throw invalid();
        }

        return new Rotation(token.userId(), issueInFamily(token.userId(), token.familyId()));
    }

    /**
     * Logout. Revokes the whole lineage so no outstanding token survives.
     *
     * <p>Uses the repository directly rather than the revoker: this path returns normally,
     * so the enclosing transaction commits and the revocation persists. A new transaction
     * here would cost a second connection for no benefit.
     */
    @Transactional
    public void revokeFamilyOf(String rawToken) {
        tokens.findByTokenHash(TokenHasher.sha256Hex(rawToken))
                .ifPresent(token -> tokens.revokeFamily(token.familyId(), Instant.now(clock)));
    }

    /**
     * Removes long-expired rows. Retention is deliberately longer than the token TTL:
     * a used token must outlive its own expiry for reuse detection to still fire on it.
     */
    @Transactional
    public int purgeExpired() {
        Instant cutoff = Instant.now(clock).minus(properties.refreshTokenTtl());
        return tokens.deleteExpiredBefore(cutoff);
    }

    private IssuedToken issueInFamily(UUID userId, UUID familyId) {
        byte[] entropy = new byte[TOKEN_BYTES];
        random.nextBytes(entropy);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);

        Instant now = Instant.now(clock);
        tokens.save(RefreshToken.issue(
                Uuid7.generate(), userId, TokenHasher.sha256Hex(raw), familyId,
                now, now.plus(properties.refreshTokenTtl())));

        return new IssuedToken(raw, properties.refreshTokenTtl());
    }

    /**
     * One error for every failure mode. An attacker probing with a guessed token learns
     * nothing about whether it existed, expired, or was revoked.
     */
    private static DomainException.Unauthorized invalid() {
        return new DomainException.Unauthorized(
                ErrorCode.INVALID_CREDENTIALS, "Session expired. Please sign in again.");
    }

    public record IssuedToken(String rawValue, java.time.Duration ttl) {
    }

    public record Rotation(UUID userId, IssuedToken refreshToken) {
    }
}

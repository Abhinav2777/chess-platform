package com.chessplatform.identity.internal;

import com.chessplatform.platform.security.AuthProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies access tokens.
 *
 * <h2>Why not hand-roll the JWT</h2>
 *
 * <p>JWT libraries have a documented history of catastrophic verification bugs —
 * accepting {@code alg: none}, or confusing an HMAC secret with an RSA public key so an
 * attacker signs tokens with the published verification key. These are exactly the
 * mistakes a from-scratch implementation makes. We use Nimbus via Spring Security's
 * {@link JwtEncoder}/{@link JwtDecoder}, which pin the algorithm explicitly.
 *
 * <p>The plumbing around it is ours, because the same {@link #verify} has to serve the
 * WebSocket first-message authentication in Phase 2, where there is no servlet filter
 * chain to hang off.
 *
 * <h2>Claims</h2>
 *
 * <p>{@code sub} is the user ID, not the username: usernames could become mutable, and a
 * token should not stop identifying its holder because they renamed themselves.
 * {@code jti} exists so a denylist is possible later without a token format change.
 */
@Service
public class JwtService {

    private static final String CLAIM_USERNAME = "username";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Clock clock;
    private final AuthProperties properties;

    public JwtService(AuthProperties properties, Clock clock) {
        SecretKey key = new SecretKeySpec(
                properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");

        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        // macAlgorithm pins HS256. Without pinning, a decoder that honours the token's
        // own `alg` header lets an attacker choose the verification algorithm — the
        // classic JWT confusion attack.
        this.decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        this.clock = clock;
        this.properties = properties;
    }

    public String issueAccessToken(UUID userId, String username) {
        Instant now = Instant.now(clock);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("chess-platform")
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_USERNAME, username)
                .build();

        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    /**
     * Verifies signature and expiry.
     *
     * <p>Returns {@link Optional#empty()} rather than throwing. A bad or expired token on
     * a public endpoint is an ordinary event — an expired tab, a probe — not an
     * exceptional one, and turning every one into a stack trace is both noisy and a way
     * for an unauthenticated caller to burn server CPU.
     */
    public Optional<VerifiedToken> verify(String tokenValue) {
        try {
            Jwt jwt = decoder.decode(tokenValue);
            return Optional.of(new VerifiedToken(
                    UUID.fromString(jwt.getSubject()),
                    jwt.getClaimAsString(CLAIM_USERNAME)));
        } catch (JwtException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public record VerifiedToken(UUID userId, String username) {
    }
}

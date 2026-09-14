package com.chessplatform.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hex digest, used to store refresh tokens without storing refresh tokens.
 *
 * <p>Not a password hash, and deliberately not bcrypt. bcrypt is slow on purpose to make
 * dictionary attacks on <em>low-entropy</em> secrets expensive. A refresh token is 256
 * bits from a CSPRNG, so there is nothing to enumerate — a fast hash is equally
 * unbreakable here. Using bcrypt would add ~250 ms to every refresh and hand an attacker
 * a CPU-exhaustion lever on a constantly-called endpoint.
 *
 * <p>The reason to hash at all is the same either way: a database dump must not yield
 * live sessions.
 */
final class TokenHasher {

    private TokenHasher() {
    }

    static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is mandated by the JLS for every conformant JVM.
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}

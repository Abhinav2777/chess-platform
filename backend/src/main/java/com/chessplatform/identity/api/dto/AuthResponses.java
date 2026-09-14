package com.chessplatform.identity.api.dto;

import java.util.UUID;

public final class AuthResponses {

    private AuthResponses() {
    }

    /**
     * The refresh token is deliberately absent: it goes back as an httpOnly cookie the
     * browser cannot read. Returning it in the body would expose it to any XSS on the
     * page, which is the entire attack this design prevents.
     *
     * @param expiresInSeconds so the client can refresh proactively instead of waiting
     *                         for a 401 mid-action
     */
    public record Session(String accessToken, long expiresInSeconds,
                          UUID userId, String username) {
    }

    public record Profile(UUID id, String username, int rating, java.time.Instant createdAt) {
    }
}

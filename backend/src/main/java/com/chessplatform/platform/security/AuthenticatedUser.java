package com.chessplatform.platform.security;

import java.util.UUID;

/**
 * The authenticated principal, available to controllers via {@code @AuthenticationPrincipal}.
 *
 * <p>Carries only what the access token asserted. Notably it does <em>not</em> carry a
 * {@code User} entity: resolving one would mean a database round-trip on every
 * authenticated request to produce something most endpoints never read.
 */
public record AuthenticatedUser(UUID id, String username) {
}

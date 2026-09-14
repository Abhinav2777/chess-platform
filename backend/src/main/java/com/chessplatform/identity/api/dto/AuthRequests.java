package com.chessplatform.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payloads for the auth endpoints.
 *
 * <p>Records, not the {@code User} entity. Binding straight to an entity is how mass
 * assignment happens: a client posting {@code {"username":"x","rating":3000}} would set
 * its own rating. A DTO can only carry the fields it declares.
 */
public final class AuthRequests {

    private AuthRequests() {
    }

    /**
     * @param username 3–32 chars, letters/digits/underscore/hyphen. Constrained because
     *                 usernames are displayed, logged and used in URLs; permitting
     *                 arbitrary Unicode invites homoglyph impersonation.
     * @param password Minimum 12, no composition rules. Length dominates entropy —
     *                 "must contain a symbol" produces {@code Password1!} and nothing
     *                 else. Maximum 72 because bcrypt silently truncates beyond that,
     *                 which would make longer passwords weaker than they appear.
     */
    public record Register(
            @NotBlank @Size(min = 3, max = 32)
            @Pattern(regexp = "^[a-zA-Z0-9_-]+$",
                    message = "may contain only letters, digits, underscore and hyphen")
            String username,

            @NotBlank @Email @Size(max = 255) String email,

            @NotBlank @Size(min = 12, max = 72) String password) {
    }

    /**
     * No constraints beyond presence. Applying the registration rules here would let an
     * attacker distinguish "malformed" from "wrong" and probe the username policy — and
     * would lock out users whose accounts predate a policy change.
     */
    public record Login(@NotBlank String username, @NotBlank String password) {
    }
}

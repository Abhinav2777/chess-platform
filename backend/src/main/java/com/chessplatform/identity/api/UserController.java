package com.chessplatform.identity.api;

import com.chessplatform.identity.IdentityFacade;
import com.chessplatform.identity.UserSummary;
import com.chessplatform.identity.api.dto.AuthResponses;
import com.chessplatform.platform.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final IdentityFacade identity;

    public UserController(IdentityFacade identity) {
        this.identity = identity;
    }

    /**
     * The caller's own profile.
     *
     * <p>{@code /me} rather than {@code /users/{id}} deliberately: the identity comes from
     * the token, so there is no path parameter to tamper with and no authorisation check
     * to forget. IDOR — reading another user's record by changing an ID in the URL — is
     * among the most common API vulnerabilities, and the shape of this endpoint makes it
     * unrepresentable rather than merely guarded against.
     */
    @GetMapping("/me")
    public AuthResponses.Profile me(@AuthenticationPrincipal AuthenticatedUser caller) {
        UserSummary user = identity.getById(caller.id());
        return new AuthResponses.Profile(
                user.id(), user.username(), user.rating(), user.createdAt());
    }
}

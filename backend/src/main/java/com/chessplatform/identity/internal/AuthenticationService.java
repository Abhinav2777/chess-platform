package com.chessplatform.identity.internal;

import com.chessplatform.identity.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the login and refresh flows.
 *
 * <p>Exists so the controller stays a transport adapter: parse the request, call one
 * method, map the result to HTTP. Business sequencing that spans several collaborators —
 * verify the password, mint an access token, start a refresh family — belongs behind one
 * transactional boundary, not spread across a controller method where a partial failure
 * leaves a token issued for an authentication that did not complete.
 */
@Service
public class AuthenticationService {

    private final UserRegistrar registrar;
    private final UserAuthenticator authenticator;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;

    public AuthenticationService(UserRegistrar registrar, UserAuthenticator authenticator,
                                 JwtService jwt, RefreshTokenService refreshTokens) {
        this.registrar = registrar;
        this.authenticator = authenticator;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
    }

    @Transactional
    public Session register(String username, String email, String password) {
        User user = registrar.register(username, email, password);
        return sessionFor(user);
    }

    @Transactional
    public Session login(String username, String password) {
        User user = authenticator.authenticate(username, password);
        return sessionFor(user);
    }

    /**
     * Exchanges a refresh token for a new pair.
     *
     * <p>The username is re-read from the database rather than carried in the old token,
     * so a refresh reflects the account's current state. A token minted an hour ago
     * should not resurrect a stale display name — or, later, a stale set of privileges.
     */
    @Transactional
    public Session refresh(String rawRefreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken);
        User user = authenticator.requireById(rotation.userId());
        return new Session(
                jwt.issueAccessToken(user.id(), user.username()),
                rotation.refreshToken(),
                user.id(),
                user.username());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.revokeFamilyOf(rawRefreshToken);
    }

    private Session sessionFor(User user) {
        return new Session(
                jwt.issueAccessToken(user.id(), user.username()),
                refreshTokens.issueNewFamily(user.id()),
                user.id(),
                user.username());
    }

    public record Session(String accessToken,
                          RefreshTokenService.IssuedToken refreshToken,
                          java.util.UUID userId,
                          String username) {
    }
}

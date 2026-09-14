package com.chessplatform.identity.api;

import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import com.chessplatform.identity.api.dto.AuthRequests;
import com.chessplatform.identity.api.dto.AuthResponses;
import com.chessplatform.identity.internal.AuthenticationService;
import com.chessplatform.platform.security.AuthProperties;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Authentication endpoints.
 *
 * <h2>Why the refresh token is a cookie and the access token is not</h2>
 *
 * <p>They face different threats, so they get different storage.
 *
 * <p>The access token is short-lived and must be attachable to a header the browser
 * cannot forge cross-site, so the client holds it in memory. Not {@code localStorage}:
 * anything there is readable by any script on the page, so one XSS exfiltrates it.
 *
 * <p>The refresh token is long-lived and therefore the more valuable secret, so it must
 * be unreachable by script at all — {@code HttpOnly}. But a cookie is sent automatically
 * on cross-site requests, which is the CSRF problem. {@code SameSite=Strict} closes that:
 * the browser withholds it entirely on cross-site navigation.
 *
 * <p>Net effect: XSS cannot read the refresh token, CSRF cannot use it, and the access
 * token that XSS could steal expires in minutes.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String REFRESH_COOKIE = "refresh_token";

    private final AuthenticationService auth;
    private final AuthProperties properties;

    public AuthController(AuthenticationService auth, AuthProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponses.Session> register(
            @Valid @RequestBody AuthRequests.Register request, HttpServletResponse response) {
        return respond(auth.register(request.username(), request.email(), request.password()),
                response, HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponses.Session> login(
            @Valid @RequestBody AuthRequests.Login request, HttpServletResponse response) {
        return respond(auth.login(request.username(), request.password()),
                response, HttpStatus.OK);
    }

    /**
     * Reads the refresh token from the cookie only — never a request body or header.
     * A credential with exactly one transport has exactly one attack surface.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponses.Session> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new DomainException.Unauthorized(
                    ErrorCode.INVALID_CREDENTIALS, "Session expired. Please sign in again.");
        }
        return respond(auth.refresh(refreshToken), response, HttpStatus.OK);
    }

    /**
     * Always 204, even with no cookie or an unknown token. Logout has no failure mode
     * worth reporting, and reporting one would confirm whether a token was valid.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            auth.logout(refreshToken);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString());
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<AuthResponses.Session> respond(AuthenticationService.Session session,
                                                          HttpServletResponse response,
                                                          HttpStatus status) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                refreshCookie(session.refreshToken().rawValue(),
                              session.refreshToken().ttl()).toString());

        return ResponseEntity.status(status).body(new AuthResponses.Session(
                session.accessToken(),
                properties.accessTokenTtl().toSeconds(),
                session.userId(),
                session.username()));
    }

    private ResponseCookie refreshCookie(String value, Duration ttl) {
        return baseCookie(value).maxAge(ttl).build();
    }

    private ResponseCookie clearRefreshCookie() {
        return baseCookie("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)      // unreadable by script: survives XSS
                .secure(true)        // HTTPS only; browsers accept Secure on localhost
                .sameSite("Strict")  // withheld cross-site: closes CSRF on this cookie
                .path("/api/auth");  // sent only to the endpoints that need it
    }
}

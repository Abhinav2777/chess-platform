package com.chessplatform.platform.security;

import com.chessplatform.identity.internal.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates requests from the {@code Authorization: Bearer} header.
 *
 * <h2>Why a custom filter rather than the OAuth2 resource server DSL</h2>
 *
 * <p>{@code http.oauth2ResourceServer(...)} would do this with less code, and for a
 * conventional resource server it would be the right answer. Two reasons it is not here.
 * We issue our own tokens, so the OAuth2 machinery models a relationship that does not
 * exist. More importantly, Phase 2 needs the same verification for WebSocket
 * first-message authentication (ADR-009), where there is no servlet filter chain at all —
 * so {@link JwtService#verify} has to be callable directly regardless. Given that, a
 * ~30-line filter over the same service is less total machinery than two auth paths with
 * different implementations.
 *
 * <h2>This filter never rejects</h2>
 *
 * <p>A missing or invalid token leaves the {@link SecurityContextHolder} empty and the
 * chain continues. Authorisation is decided by {@code authorizeHttpRequests}, which knows
 * which endpoints are public; the filter does not. Rejecting here would 401 requests to
 * the login endpoint itself.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwt;

    public JwtAuthenticationFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        jwt.verify(header.substring(PREFIX.length())).ifPresent(verified -> {
            AuthenticatedUser principal = new AuthenticatedUser(verified.userId(), verified.username());
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
            // Every log line for the rest of this request carries the user id. Without
            // this, correlating "what did user X do" means joining on request ids by hand.
            MDC.put("userId", verified.userId().toString());
        });

        try {
            chain.doFilter(request, response);
        } finally {
            // Both are thread-local and the thread is pooled or reused. Leaking either
            // means the next request on this thread inherits the previous user's
            // identity — an authorisation bug in the first case, a misattributed audit
            // trail in the second.
            MDC.remove("userId");
            SecurityContextHolder.clearContext();
        }
    }
}

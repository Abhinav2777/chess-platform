package com.chessplatform.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * HTTP security.
 *
 * <p>Written against Spring Security 7, which removed {@code .and()} chaining,
 * {@code authorizeRequests}, {@code AntPathRequestMatcher}/{@code MvcRequestMatcher} and
 * {@code AccessDecisionManager}. These are hard removals with no runtime fallback, so
 * most Security configuration found online will not compile here. Everything below is
 * lambda DSL.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String PROBLEM_TYPE_PREFIX = "https://chess-platform.dev/errors/";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtFilter,
                                                   Environment environment)
            throws Exception {
        boolean actuatorOnItsOwnPort = actuatorOnItsOwnPort(environment);
        return http
                // CSRF protects cookie-authenticated state changes, because a browser
                // attaches cookies to cross-site requests automatically. Our API
                // authenticates from an Authorization header, which a cross-site page
                // cannot set — so there is nothing for CSRF to protect here.
                //
                // The refresh cookie IS cookie-borne and therefore IS exposed. It is
                // defended by SameSite=Strict instead (see AuthController), which stops
                // the browser sending it on any cross-site request at all.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // No session, no JSESSIONID. Every request carries its own credential, so
                // any instance can serve any request — the property that later makes
                // WebSocket fanout and rolling deploys work without sticky sessions.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(authorize -> authorize
                        // Actuator on its own port (the aws and k8s profiles): open to whatever
                        // can reach that port, because nothing outside can — no Service or
                        // Ingress exposes it, and on AWS the security group admits only the ALB's
                        // health checks. Network isolation is the access control, and a metrics
                        // scraper needs no credentials (found in 9.2: the pods' histograms answered
                        // 401). On the application port these paths are not mapped at all: 404.
                        // When actuator shares the application port (local), only health is open.
                        .requestMatchers(actuatorOnItsOwnPort ? "/actuator/**" : "/__never__").permitAll()
                        .requestMatchers("/api/auth/register", "/api/auth/login",
                                         "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        // The SPA, baked into the image (Phase 7): the one origin serves the
                        // page, the API and the socket. Named files only, GET only — not a
                        // blanket "/**", which would open every future endpoint by default.
                        // index.html is listed because "/" is served by forwarding to it, and
                        // the forward passes through this chain again.
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**").permitAll()
                        // The WebSocket handshake carries no credential and cannot: the
                        // browser API will not set an Authorization header on it. The
                        // socket authenticates in its first frame instead (ADR-009), so
                        // the filter chain must let the handshake through. "permitAll"
                        // here means "permitted to open a socket", not "permitted to do
                        // anything" — every command after AUTH is checked by the handler.
                        .requestMatchers("/ws/**", "/ws").permitAll()
                        // Everything not listed requires authentication. Deny-by-default:
                        // a new endpoint is protected until someone deliberately opens it,
                        // rather than exposed until someone remembers to close it.
                        .anyRequest().authenticated())

                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                writeProblem(response, HttpStatus.UNAUTHORIZED,
                                        "Authentication required"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeProblem(response, HttpStatus.FORBIDDEN,
                                        "Access denied")))

                // Disabled explicitly rather than left to defaults. Spring Security
                // auto-configures both when a filter chain is not fully specified, and a
                // login form on a JSON API is a confusing 200-with-HTML response to what
                // should be a 401.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())

                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * {@code allowCredentials(true)} is required because the refresh token travels in a
     * cookie, and it is why origins must be listed explicitly — the CORS spec forbids
     * pairing credentials with a wildcard origin, precisely so that a site cannot read
     * authenticated responses from another.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Writes an RFC 7807 body by hand, with no JSON library.
     *
     * <p>These two responses are fixed four-field objects built entirely from constants
     * this class controls. No user input reaches them, so there is nothing to escape and
     * nothing a serialiser would do better.
     *
     * <p>The reason not to inject one is concrete. Spring Boot 4 defaults to Jackson 3,
     * whose types live under {@code tools.jackson.databind}. An injected
     * {@code com.fasterxml.jackson.databind.ObjectMapper} is a Jackson 2 type with no
     * bean behind it — and that mistake compiles cleanly and fails at startup. Security
     * filter internals are the wrong place to depend on a library whose coordinates move
     * between major versions.
     *
     * <p>{@code ApiExceptionHandler} still returns {@code ProblemDetail} normally. There,
     * Spring's message converters do the serialising and the Jackson version is Spring's
     * concern rather than ours — which is the distinction worth drawing: depend on the
     * framework's abstraction, not on the library behind it.
     */
    private static void writeProblem(jakarta.servlet.http.HttpServletResponse response,
                                     HttpStatus status, String detail)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"type\":\"" + PROBLEM_TYPE_PREFIX + status.name().toLowerCase() + "\","
                + "\"title\":\"" + status.getReasonPhrase() + "\","
                + "\"status\":" + status.value() + ","
                + "\"detail\":\"" + detail + "\"}");
    }

    /**
     * Whether actuator is served by a separate management server. {@code 0} (a random port, as in
     * tests) is always separate; otherwise it is separate when it differs from the server port.
     */
    static boolean actuatorOnItsOwnPort(Environment environment) {
        Integer management = environment.getProperty("management.server.port", Integer.class);
        if (management == null || management < 0) {
            return false;
        }
        return management == 0 || !management.equals(environment.getProperty("server.port", Integer.class, 8080));
    }
}

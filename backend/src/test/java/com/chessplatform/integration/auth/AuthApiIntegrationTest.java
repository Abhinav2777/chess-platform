package com.chessplatform.integration.auth;

import com.chessplatform.identity.domain.RefreshTokenRepository;
import com.chessplatform.identity.domain.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.FilterChainProxy;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP tests for authentication.
 *
 * <p>Uses a real servlet stack and a real database rather than mocks, because the things
 * most likely to be wrong here are not business logic: filter ordering, cookie
 * attributes, which endpoints the security chain leaves open, and what an error response
 * actually looks like on the wire. None of that is observable from a unit test of the
 * service layer.
 *
 * <p>Does not extend {@code IntegrationTestBase} — that one runs with no web environment.
 */
@SpringBootTest
@DisplayName("Auth API")
class AuthApiIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy springSecurityFilterChain;
    @Autowired
    private UserRepository users;
    @Autowired
    private RefreshTokenRepository refreshTokens;

    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            // The security filter chain is added explicitly. Without it MockMvc bypasses
            // Spring Security entirely and every endpoint looks public — a test suite
            // that would pass with authorisation completely broken.
            mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(springSecurityFilterChain)
                    .build();
        }
        return mvc;
    }

    @AfterEach
    void cleanUp() {
        refreshTokens.deleteAll();
        users.deleteAll();
    }

    private MvcResult register(String username, String email, String password) throws Exception {
        return mvc().perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s","password":"%s"}
                                """.formatted(username, email, password)))
                .andReturn();
    }

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("returns 201 with an access token and sets a hardened refresh cookie")
        void registersSuccessfully() throws Exception {
            mvc().perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"magnus","email":"m@example.com",
                                     "password":"correct-horse-battery"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.username").value("magnus"))
                    // The access token must not be accompanied by the refresh token in
                    // the body — that would expose it to any XSS on the page.
                    .andExpect(jsonPath("$.refreshToken").doesNotExist())
                    .andExpect(cookie().exists("refresh_token"))
                    .andExpect(cookie().httpOnly("refresh_token", true))
                    .andExpect(cookie().secure("refresh_token", true));
        }

        @Test
        @DisplayName("rejects a weak password with per-field validation detail")
        void rejectsShortPassword() throws Exception {
            mvc().perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"magnus","email":"m@example.com","password":"short"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.password").isNotEmpty());
        }

        @Test
        @DisplayName("returns 409 with a machine-readable code on a duplicate username")
        void rejectsDuplicate() throws Exception {
            register("magnus", "a@example.com", "correct-horse-battery");

            mvc().perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"magnus","email":"b@example.com",
                                     "password":"correct-horse-battery"}
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
        }

        @Test
        @DisplayName("cannot set its own rating via mass assignment")
        void ignoresUnknownFields() throws Exception {
            mvc().perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"cheater","email":"c@example.com",
                                     "password":"correct-horse-battery","rating":3000}
                                    """))
                    .andExpect(status().isCreated());

            assertThat(users.findByUsername("cheater")).get()
                    .extracting(u -> u.rating())
                    .isEqualTo(1200);
        }
    }

    @Nested
    @DisplayName("authorisation")
    class Authorisation {

        @Test
        @DisplayName("rejects an unauthenticated request with problem+json")
        void rejectsAnonymous() throws Exception {
            mvc().perform(get("/api/users/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }

        @Test
        @DisplayName("rejects a forged token")
        void rejectsForgedToken() throws Exception {
            // Structurally valid JWT, signed with the wrong key.
            mvc().perform(get("/api/users/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer eyJhbGciOiJIUzI1NiJ9."
                                    + "eyJzdWIiOiJhdHRhY2tlciJ9.not-a-valid-signature"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("accepts a genuine token and returns the caller's own profile")
        void acceptsValidToken() throws Exception {
            MvcResult registered = register("magnus", "m@example.com", "correct-horse-battery");
            String accessToken = JsonPath.read(
                    registered.getResponse().getContentAsString(), "$.accessToken");

            mvc().perform(get("/api/users/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("magnus"))
                    .andExpect(jsonPath("$.rating").value(1200));
        }
    }

    @Nested
    @DisplayName("refresh token rotation")
    class Rotation {

        @Test
        @DisplayName("exchanges a refresh cookie for a new access token and a new cookie")
        void rotatesSuccessfully() throws Exception {
            MvcResult registered = register("magnus", "m@example.com", "correct-horse-battery");
            var first = registered.getResponse().getCookie("refresh_token");

            MvcResult refreshed = mvc().perform(post("/api/auth/refresh").cookie(first))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andReturn();

            var second = refreshed.getResponse().getCookie("refresh_token");
            assertThat(second).isNotNull();
            assertThat(second.getValue())
                    .as("rotation must issue a different token, not re-return the old one")
                    .isNotEqualTo(first.getValue());
        }

        /**
         * The security property that justifies rotation existing at all. Presenting an
         * already-rotated token is proof the lineage leaked, so the entire family is
         * revoked — including the token the attacker just obtained.
         */
        @Test
        @DisplayName("detects reuse and revokes the whole family")
        void detectsReuseAndRevokesFamily() throws Exception {
            MvcResult registered = register("magnus", "m@example.com", "correct-horse-battery");
            var stolen = registered.getResponse().getCookie("refresh_token");

            // Attacker refreshes first and obtains a working token.
            MvcResult attacker = mvc().perform(post("/api/auth/refresh").cookie(stolen))
                    .andExpect(status().isOk())
                    .andReturn();
            var attackerToken = attacker.getResponse().getCookie("refresh_token");

            // Victim then presents the original — impossible in normal operation.
            mvc().perform(post("/api/auth/refresh").cookie(stolen))
                    .andExpect(status().isUnauthorized());

            // The attacker's token must die too, or detection accomplishes nothing.
            mvc().perform(post("/api/auth/refresh").cookie(attackerToken))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("rejects a refresh with no cookie")
        void rejectsMissingCookie() throws Exception {
            mvc().perform(post("/api/auth/refresh"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("logout revokes the family and clears the cookie")
        void logoutRevokes() throws Exception {
            MvcResult registered = register("magnus", "m@example.com", "correct-horse-battery");
            var cookie = registered.getResponse().getCookie("refresh_token");

            mvc().perform(post("/api/auth/logout").cookie(cookie))
                    .andExpect(status().isNoContent())
                    .andExpect(cookie().maxAge("refresh_token", 0));

            mvc().perform(post("/api/auth/refresh").cookie(cookie))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("logout is a no-op without a cookie and never reports failure")
        void logoutIsIdempotent() throws Exception {
            mvc().perform(post("/api/auth/logout"))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("error contract")
    class ErrorContract {

        @Test
        @DisplayName("never leaks internal detail in a failure body")
        void doesNotLeakInternals() throws Exception {
            MvcResult result = mvc().perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"nobody","password":"whatever-at-all"}
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            assertThat(body)
                    .doesNotContain("Exception")
                    .doesNotContain("org.springframework")
                    .doesNotContain("com.chessplatform")
                    .doesNotContain("SELECT");
        }
    }
}

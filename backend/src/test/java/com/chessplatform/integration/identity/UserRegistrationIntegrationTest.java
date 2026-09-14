package com.chessplatform.integration.identity;

import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import com.chessplatform.identity.IdentityFacade;
import com.chessplatform.identity.UserSummary;
import com.chessplatform.identity.domain.User;
import com.chessplatform.identity.domain.UserRepository;
import com.chessplatform.identity.internal.UserAuthenticator;
import com.chessplatform.identity.internal.UserRegistrar;
import com.chessplatform.integration.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Identity module")
class UserRegistrationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserRegistrar registrar;
    @Autowired
    private UserAuthenticator authenticator;
    @Autowired
    private IdentityFacade identity;
    @Autowired
    private UserRepository users;

    @AfterEach
    void cleanUp() {
        // The container is shared across the whole JVM, so each test cleans up after
        // itself. @Transactional rollback would not work here: the concurrency test
        // below commits from other threads, outside any test-managed transaction.
        users.deleteAll();
    }

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("persists a user with a hashed password and starting rating")
        void registersUser() {
            User user = registrar.register("Magnus", "Magnus@Example.COM", "correct-horse-battery");

            assertThat(user.id()).isNotNull();
            assertThat(user.rating()).isEqualTo(1200);
            assertThat(user.createdAt()).isNotNull();

            // The plaintext must not be recoverable, and the algorithm must be recorded.
            assertThat(user.passwordHash())
                    .startsWith("{bcrypt}")
                    .doesNotContain("correct-horse-battery");
        }

        @Test
        @DisplayName("normalises username and email to lower case")
        void normalisesIdentifiers() {
            registrar.register("  Magnus  ", "Magnus@Example.COM", "correct-horse-battery");

            assertThat(users.findByUsername("magnus")).isPresent();
            assertThat(users.findByEmail("magnus@example.com")).isPresent();
            // Registration must not be case-sensitive, or "Magnus" and "magnus" become
            // two accounts that look identical in every UI that displays them.
            assertThat(users.findByUsername("Magnus")).isEmpty();
        }

        @Test
        @DisplayName("rejects a duplicate username with a precise error")
        void rejectsDuplicateUsername() {
            registrar.register("magnus", "a@example.com", "correct-horse-battery");

            assertThatThrownBy(() ->
                    registrar.register("MAGNUS", "b@example.com", "another-password"))
                    .isInstanceOfSatisfying(DomainException.Conflict.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.USERNAME_TAKEN));
        }

        @Test
        @DisplayName("rejects a duplicate email with a precise error")
        void rejectsDuplicateEmail() {
            registrar.register("magnus", "shared@example.com", "correct-horse-battery");

            assertThatThrownBy(() ->
                    registrar.register("hikaru", "SHARED@example.com", "another-password"))
                    .isInstanceOfSatisfying(DomainException.Conflict.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.EMAIL_TAKEN));
        }

        /**
         * The test that justifies the design.
         *
         * <p>Sixteen threads race to claim one username, released simultaneously by a
         * latch so they interleave inside the check-then-insert window. The pre-check
         * cannot prevent this — all sixteen can read "not taken" before any of them
         * writes. Only {@code uq_users_username} can, and this asserts that it does.
         *
         * <p>Without the unique constraint this test fails with multiple successes and
         * duplicate rows in the table. That is the whole argument for
         * database-enforced invariants over application-enforced ones, made executable.
         */
        @Test
        @DisplayName("permits exactly one winner when many threads claim one username")
        void onlyOneRegistrationWinsTheRace() throws Exception {
            int contenders = 16;
            var startLine = new CountDownLatch(1);

            List<Callable<UUID>> attempts = java.util.stream.IntStream.range(0, contenders)
                    .<Callable<UUID>>mapToObj(i -> () -> {
                        startLine.await();
                        return registrar.register("contested", "racer" + i + "@example.com", "pw-" + i).id();
                    })
                    .toList();

            List<Future<UUID>> futures;
            try (ExecutorService pool = Executors.newFixedThreadPool(contenders)) {
                futures = attempts.stream().map(pool::submit).toList();
                startLine.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }

            long succeeded = futures.stream().filter(f -> {
                try {
                    f.get();
                    return true;
                } catch (Exception rejected) {
                    return false;
                }
            }).count();

            assertThat(succeeded)
                    .as("exactly one thread may claim the username")
                    .isEqualTo(1);
            assertThat(users.findByUsername("contested")).isPresent();
            assertThat(users.count())
                    .as("no duplicate rows reached the table")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        @DisplayName("accepts correct credentials regardless of username case")
        void authenticatesValidCredentials() {
            User registered = registrar.register("magnus", "m@example.com", "correct-horse-battery");

            User authenticated = authenticator.authenticate("MAGNUS", "correct-horse-battery");

            assertThat(authenticated.id()).isEqualTo(registered.id());
        }

        @Test
        @DisplayName("rejects a wrong password")
        void rejectsWrongPassword() {
            registrar.register("magnus", "m@example.com", "correct-horse-battery");

            assertThatThrownBy(() -> authenticator.authenticate("magnus", "wrong"))
                    .isInstanceOf(DomainException.Unauthorized.class);
        }

        /**
         * Both failure modes must be indistinguishable to the caller. If the messages or
         * codes differed, the login endpoint would confirm which accounts exist.
         */
        @Test
        @DisplayName("reports an unknown user identically to a wrong password")
        void doesNotLeakAccountExistence() {
            registrar.register("magnus", "m@example.com", "correct-horse-battery");

            var wrongPassword = catchDomainException(() ->
                    authenticator.authenticate("magnus", "wrong"));
            var noSuchUser = catchDomainException(() ->
                    authenticator.authenticate("nobody", "wrong"));

            assertThat(noSuchUser.code()).isEqualTo(wrongPassword.code());
            assertThat(noSuchUser.getMessage()).isEqualTo(wrongPassword.getMessage());
        }

        private DomainException catchDomainException(Runnable action) {
            try {
                action.run();
                throw new AssertionError("expected a DomainException");
            } catch (DomainException expected) {
                return expected;
            }
        }
    }

    @Nested
    @DisplayName("facade")
    class Facade {

        @Test
        @DisplayName("exposes a summary without email or password hash")
        void returnsSummary() {
            User registered = registrar.register("magnus", "m@example.com", "correct-horse-battery");

            UserSummary summary = identity.getById(registered.id());

            assertThat(summary.username()).isEqualTo("magnus");
            assertThat(summary.rating()).isEqualTo(1200);
        }

        @Test
        @DisplayName("throws NotFound for an unknown id")
        void throwsForUnknownUser() {
            assertThatThrownBy(() -> identity.getById(UUID.randomUUID()))
                    .isInstanceOf(DomainException.NotFound.class);
        }

        @Test
        @DisplayName("resolves many ids in one query")
        void resolvesInBulk() {
            User a = registrar.register("alice", "a@example.com", "password-one");
            User b = registrar.register("bob", "b@example.com", "password-two");

            Map<UUID, UserSummary> found = identity.findAllById(List.of(a.id(), b.id()));

            assertThat(found).hasSize(2);
            assertThat(found.get(a.id()).username()).isEqualTo("alice");
            assertThat(found.get(b.id()).username()).isEqualTo("bob");
        }

        @Test
        @DisplayName("returns an empty map for an empty request without querying")
        void handlesEmptyInput() {
            assertThat(identity.findAllById(List.of())).isEmpty();
        }
    }
}

package com.chessplatform.identity.internal;

import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import com.chessplatform.common.error.UniqueViolation;
import com.chessplatform.common.id.Uuid7;
import com.chessplatform.identity.domain.User;
import com.chessplatform.identity.domain.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/**
 * Creates user accounts.
 *
 * <h2>The interesting problem: check-then-insert is a race</h2>
 *
 * <p>The obvious implementation asks "does this username exist?" and inserts if not.
 * That is wrong, and it is wrong in a way that only shows up under concurrency:
 *
 * <pre>
 *   T1: SELECT ... WHERE username = 'magnus'  -> empty
 *   T2: SELECT ... WHERE username = 'magnus'  -> empty
 *   T1: INSERT ... 'magnus'                   -> ok
 *   T2: INSERT ... 'magnus'                   -> ???
 * </pre>
 *
 * <p>Both reads happen before either write, so both pass the check. What saves us is not
 * the check — it is {@code uq_users_username} in the schema. The database is the only
 * thing that can make "no two users share a username" true, because it is the only
 * component that serialises the writes.
 *
 * <p>So the pre-check is <strong>not</strong> a correctness mechanism. We keep it purely
 * so the common case returns a precise error naming which field collided; the constraint
 * violation is caught as the real guard. This is the same shape as move idempotency in
 * ADR-005, where {@code uq_moves_client_id} is the authority and application logic is
 * ergonomics on top.
 *
 * <p>The catch block cannot tell <em>which</em> constraint fired without parsing a
 * vendor-specific message, so a lost race reports a generic conflict. That is acceptable:
 * it is rare, and the alternative is coupling to PostgreSQL error text.
 *
 * <h2>Normalisation</h2>
 *
 * <p>Username and email are lowercased before storing and before looking up.
 * {@code Locale.ROOT} is not decoration — under a Turkish locale, {@code "I".toLowerCase()}
 * yields a dotless {@code "ı"}, so a user registering as {@code "IVAN"} on a
 * Turkish-locale server would be unfindable from any other. Locale-sensitive case
 * conversion on identifiers is a real, and genuinely baffling, production bug.
 */
@Service
public class UserRegistrar {

    /** Every new player starts here. Standard Elo convention; see the rating module. */
    private static final int INITIAL_RATING = 1200;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public UserRegistrar(UserRepository users, PasswordEncoder passwordEncoder, Clock clock,
                         PlatformTransactionManager transactionManager) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Hashes, then registers in a short transaction of its own (or the caller's, if one is
     * active — which is why {@link AuthenticationService} hashes first itself).
     */
    public User register(String rawUsername, String rawEmail, String rawPassword) {
        String passwordHash = hash(rawPassword);
        return transactions.execute(status -> registerHashed(rawUsername, rawEmail, passwordHash));
    }

    /**
     * bcrypt, deliberately slow (cost 12) — and therefore never inside a transaction. A JPA
     * transaction takes its pooled connection when it begins; hashing inside one holds that
     * connection idle for the whole hash. On a 0.5-vCPU Fargate task under contention that was
     * seconds, and ~3 registrations a second exhausted a 10-connection pool (Phase 9.4,
     * docs/perf/optimisation-02.md). Hash first, then open the transaction.
     */
    public String hash(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    /** The database half of registration. Joins the caller's transaction; requires one. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public User registerHashed(String rawUsername, String rawEmail, String passwordHash) {
        String username = normalise(rawUsername);
        String email = normalise(rawEmail);

        // Ergonomics, not safety. See the class comment.
        if (users.findByUsername(username).isPresent()) {
            throw new DomainException.Conflict(
                    ErrorCode.USERNAME_TAKEN, "That username is already registered.");
        }
        if (users.findByEmail(email).isPresent()) {
            throw new DomainException.Conflict(
                    ErrorCode.EMAIL_TAKEN, "That email address is already registered.");
        }

        User user = User.register(
                Uuid7.generate(),
                username,
                email,
                passwordHash,
                INITIAL_RATING,
                Instant.now(clock));

        try {
            // saveAndFlush, not save. `save` defers the INSERT to transaction commit,
            // which would throw the constraint violation outside this try block and
            // surface as an opaque 500 instead of a 409. Flushing here puts the failure
            // where we can translate it.
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException lostTheRace) {
            // Only a unique violation is the race described above; anything else is a bug and
            // must not read as "registered a moment ago" (UniqueViolation).
            if (!UniqueViolation.isCause(lostTheRace)) {
                throw lostTheRace;
            }
            throw new DomainException.Conflict(
                    ErrorCode.CONFLICT,
                    "That username or email was registered a moment ago. Please try again.");
        }
    }

    private static String normalise(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }
}

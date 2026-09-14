package com.chessplatform.identity.internal;

import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import com.chessplatform.identity.domain.User;
import com.chessplatform.identity.domain.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * Verifies a username/password pair.
 *
 * <h2>Two things this must not leak</h2>
 *
 * <p><strong>1. Which half was wrong.</strong> "No such user" and "wrong password" return
 * an identical error. Distinguishing them turns the login endpoint into a user
 * enumeration oracle: an attacker submits candidate emails with garbage passwords and
 * harvests a list of real accounts, which is valuable on its own and is the first step
 * in credential stuffing.
 *
 * <p><strong>2. Timing.</strong> The naive implementation returns immediately when no
 * user is found, and spends ~100 ms hashing when one is. That difference is trivially
 * measurable over a network and re-opens the enumeration oracle that the identical
 * message just closed. So when the user does not exist we hash the supplied password
 * against a dummy hash anyway, paying the same cost before failing.
 *
 * <p>This is not perfect constant-time behaviour — bcrypt's own cost varies slightly, and
 * a determined attacker with enough samples can still find signal. It removes the
 * order-of-magnitude difference, which is what makes the attack practical.
 */
@Service
public class UserAuthenticator {

    /**
     * A real bcrypt hash of a value nobody knows, used only to burn the same CPU time we
     * would have spent on a genuine comparison. Must be a valid hash of the configured
     * algorithm, or the encoder short-circuits and the timing difference returns.
     */
    private static final String DUMMY_HASH =
            "{bcrypt}$2a$12$C6UzMDM.H6dfI/f/IKcEe.3jL3Gq3WoTHt5b7WPBOyBqM7xLh5BnG";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public UserAuthenticator(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public User authenticate(String rawUsername, String rawPassword) {
        String username = rawUsername.strip().toLowerCase(Locale.ROOT);
        Optional<User> candidate = users.findByUsername(username);

        // Always hash, present or not. Note the deliberate lack of short-circuiting:
        // `candidate.isPresent() && matches(...)` would skip the hash when absent,
        // which is exactly the timing leak described above.
        boolean passwordMatches = passwordEncoder.matches(
                rawPassword,
                candidate.map(User::passwordHash).orElse(DUMMY_HASH));

        if (candidate.isEmpty() || !passwordMatches) {
            throw new DomainException.Unauthorized(
                    ErrorCode.INVALID_CREDENTIALS, "Incorrect username or password.");
        }
        return candidate.get();
    }

    /**
     * Loads a user already known to be authenticated, for the refresh flow.
     *
     * <p>Unauthorized rather than NotFound on purpose. Reaching here means a valid
     * refresh token references a user who no longer exists — a deleted account. The
     * caller holds a credential; the correct response is that the credential is no longer
     * good, not a 404 that confirms the account was removed.
     */
    @Transactional(readOnly = true)
    public User requireById(java.util.UUID userId) {
        return users.findById(userId).orElseThrow(() -> new DomainException.Unauthorized(
                ErrorCode.INVALID_CREDENTIALS, "Session expired. Please sign in again."));
    }
}

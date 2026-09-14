package com.chessplatform.identity;

import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import com.chessplatform.identity.domain.User;
import com.chessplatform.identity.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The identity module's sole entry point for other modules.
 *
 * <p>{@code game} and {@code matchmaking} depend on this type and nothing else under
 * {@code com.chessplatform.identity}. Everything in {@code identity.internal} is
 * unreachable from outside, enforced by ArchUnit (ADR-001).
 *
 * <p>The point is not encapsulation for its own sake. If identity is ever extracted into
 * its own service, this interface is already the contract — the method signatures become
 * the HTTP or gRPC surface, and no caller changes. That is what "extraction stays cheap"
 * means concretely.
 *
 * <p>Note the deliberate absence of {@code register} and {@code authenticate}. Those are
 * reachable only through the HTTP layer, because no other module has any business
 * creating accounts. A facade should expose what collaborators need, not everything the
 * module can do.
 */
@Service
public class IdentityFacade {

    private final UserRepository users;

    public IdentityFacade(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Optional<UserSummary> findById(UUID userId) {
        return users.findById(userId).map(IdentityFacade::toSummary);
    }

    /**
     * Looks a player up by username, for challenges.
     *
     * <p>Lowercases the input because {@code UserRegistrar} stores the normalised form —
     * the lookup must normalise identically or the unique index is missed and the user
     * appears not to exist. {@code Locale.ROOT} for the reason given there: a
     * Turkish-locale JVM lowercases "I" to a dotless "i".
     */
    @Transactional(readOnly = true)
    public Optional<UserSummary> findByUsername(String username) {
        return users.findByUsername(username.strip().toLowerCase(java.util.Locale.ROOT))
                .map(IdentityFacade::toSummary);
    }

    @Transactional(readOnly = true)
    public UserSummary getById(UUID userId) {
        return findById(userId).orElseThrow(() -> new DomainException.NotFound(
                ErrorCode.USER_NOT_FOUND, "No such user."));
    }

    /**
     * Bulk lookup. Exists specifically so callers rendering a game, a leaderboard, or a
     * move list do not loop over {@link #findById} — the N+1 query pattern, which is
     * invisible at two users and ruinous at two hundred. Giving callers a batch method
     * is more effective than telling them not to loop.
     */
    @Transactional(readOnly = true)
    public Map<UUID, UserSummary> findAllById(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<User> found = users.findAllById(userIds);
        return found.stream()
                .map(IdentityFacade::toSummary)
                .collect(Collectors.toMap(UserSummary::id, Function.identity()));
    }

    private static UserSummary toSummary(User user) {
        return new UserSummary(user.id(), user.username(), user.rating(), user.createdAt());
    }
}

package com.chessplatform.integration.auth;

import com.chessplatform.identity.internal.AuthenticationService;
import com.chessplatform.integration.IntegrationTestBase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * bcrypt must never run while a database connection is held (Phase 9.4).
 *
 * <p>Found on AWS: on a 0.5-vCPU Fargate task, cost-12 bcrypt takes long enough under contention
 * that registration — hashing inside its transaction — kept all 10 pool connections idle-but-held
 * at about 3 registrations a second, and everything else timed out waiting. On a 16-core laptop the
 * same code never showed it.
 *
 * <p>The spy records, at the instant of hashing, whether a transaction is active and how many pool
 * connections are checked out. Single-threaded test: nothing else holds connections.
 */
@DisplayName("Password hashing and the connection pool")
class PasswordHashingConnectionIntegrationTest extends IntegrationTestBase {

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AuthenticationService auth;
    @Autowired
    private DataSource dataSource;

    private final List<String> duringHashing = new ArrayList<>();

    @BeforeEach
    void recordStateDuringHashing() {
        doAnswer(invocation -> {
            record("encode");
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(any());
        doAnswer(invocation -> {
            record("matches");
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(any(), any());
    }

    @Test
    @DisplayName("registering hashes with no transaction open and no connection held")
    void registration() {
        String name = "ph" + UUID.randomUUID().toString().substring(0, 8);

        auth.register(name, name + "@example.com", "correct-horse-battery");

        assertThat(duringHashing).containsExactly("encode: transaction=false activeConnections=0");
    }

    @Test
    @DisplayName("signing in verifies the password with no transaction open and no connection held")
    void login() {
        String name = "pl" + UUID.randomUUID().toString().substring(0, 8);
        auth.register(name, name + "@example.com", "correct-horse-battery");
        duringHashing.clear();

        auth.login(name, "correct-horse-battery");

        assertThat(duringHashing).containsExactly("matches: transaction=false activeConnections=0");
    }

    private void record(String operation) throws java.sql.SQLException {
        int active = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
        duringHashing.add(operation + ": transaction=" + TransactionSynchronizationManager.isActualTransactionActive()
                + " activeConnections=" + active);
    }
}

package com.chessplatform.unit.common;

import com.chessplatform.common.error.ApiExceptionHandler;
import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 503 means "retry later"; when the server knows how much later, it says so (Phase 9.4: the
 * password-hashing queue sheds load with a one-second hint).
 */
@DisplayName("503 responses")
class UnavailableResponseTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/register");

    @Test
    @DisplayName("load shedding: 503, Retry-After in whole seconds rounded up, code SERVER_BUSY")
    void withRetryAfter() {
        ResponseEntity<ProblemDetail> response = handler.handleUnavailable(new DomainException.Unavailable(
                ErrorCode.SERVER_BUSY, "busy", Duration.ofMillis(1_200)), request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(response.getBody().getProperties()).containsEntry("code", "SERVER_BUSY");
    }

    @Test
    @DisplayName("no useful wait known (matchmaking without Valkey): 503 without Retry-After")
    void withoutRetryAfter() {
        ResponseEntity<ProblemDetail> response = handler.handleUnavailable(new DomainException.Unavailable(
                ErrorCode.MATCHMAKING_UNAVAILABLE, "down"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().containsHeader("Retry-After")).isFalse();
        assertThat(response.getBody().getProperties()).containsEntry("code", "MATCHMAKING_UNAVAILABLE");
    }
}

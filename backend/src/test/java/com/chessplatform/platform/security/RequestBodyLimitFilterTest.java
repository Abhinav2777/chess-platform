package com.chessplatform.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every request body is bounded before anything reads it (Phase 10.1): a 100 MB login body once
 * cost ~600 MB of heap, more than an AWS task has.
 */
@DisplayName("Request body limit")
class RequestBodyLimitFilterTest {

    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(DataSize.ofBytes(100));

    @Test
    @DisplayName("a declared length over the limit is 413 and never reaches the application")
    void declaredTooLarge() throws Exception {
        MockHttpServletRequest request = post(new byte[101]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"status\":413");
        assertThat(response.getHeader("Connection")).isEqualTo("close");
        assertThat(chain.getRequest()).as("the chain must not run").isNull();
    }

    @Test
    @DisplayName("a declared length within the limit passes through untouched")
    void declaredWithinLimit() throws Exception {
        MockHttpServletRequest request = post(new byte[100]);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    @DisplayName("a chunked body (no length) is cut off as it is read, past the limit")
    void chunkedTooLarge() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(chunked(new byte[101]), new MockHttpServletResponse(), chain);

        assertThatThrownBy(() -> chain.getRequest().getInputStream().readAllBytes())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds 100 bytes");
    }

    @Test
    @DisplayName("a chunked body within the limit reads in full")
    void chunkedWithinLimit() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(chunked(new byte[100]), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest().getInputStream().readAllBytes()).hasSize(100);
    }

    private static MockHttpServletRequest post(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setContent(body);  // sets Content-Length
        return request;
    }

    /** No Content-Length, as with Transfer-Encoding: chunked. */
    private static MockHttpServletRequest chunked(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(body);
        request.addHeader("Transfer-Encoding", "chunked");
        return request;
    }
}

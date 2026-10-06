package com.chessplatform.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Caps the size of every request body, before anything reads it (Phase 10.1, OWASP API4).
 *
 * <h2>Why this exists</h2>
 *
 * <p>Nothing else bounds a JSON body. Tomcat's {@code maxPostSize} covers form parameters only,
 * the ALB has no body limit, and Spring reads {@code @RequestBody} in full <em>before</em> the
 * controller runs — so the login rate limit, which lives in the controller, never sees it.
 * Measured: four concurrent 100 MB bodies to the unauthenticated {@code /api/auth/login} took a
 * local JVM's heap from 58 MB to 2,463 MB. An API task on AWS has a 512 MB heap: one such request
 * ends it ({@code ExitOnOutOfMemoryError}).
 *
 * <p>The largest legitimate body is a registration, a few hundred bytes. The default 16 KB is far
 * above that and far below anything that costs memory.
 *
 * <h2>How</h2>
 *
 * <ul>
 *   <li>A declared {@code Content-Length} over the limit: 413 at once, nothing read.</li>
 *   <li>A body that declares no length (chunked): the stream is capped as it is read and throws
 *       past the limit, which Spring reports as an unreadable body (400). Bounded either way.</li>
 *   <li>A declared length within the limit needs nothing: the container reads no further.</li>
 * </ul>
 *
 * <p>First in the chain — ahead of Spring Security — so an oversized body is refused before
 * authentication or anything else does work for it. WebSocket frames are bounded separately
 * ({@code WebSocketConfig}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestBodyLimitFilter(@Value("${chess.http.max-request-body:16KB}") DataSize maxRequestBody) {
        this.maxBytes = maxRequestBody.toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            reject(response);
            return;
        }
        boolean undeclaredBody = request.getContentLengthLong() < 0
                && request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null;
        chain.doFilter(undeclaredBody ? new BoundedRequest(request, maxBytes) : request, response);
    }

    /** Built by hand for the same reason as {@code SecurityConfig.writeProblem}: constants only. */
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // The unread body is not worth draining: close the connection instead.
        response.setHeader(HttpHeaders.CONNECTION, "close");
        response.getWriter().write(
                "{\"type\":\"https://chess-platform.dev/errors/content_too_large\","
                + "\"title\":\"Content Too Large\",\"status\":413,"
                + "\"detail\":\"Request body exceeds " + maxBytes + " bytes.\"}");
    }

    private static final class BoundedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream stream;

        BoundedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new BoundedInputStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class BoundedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read;

        BoundedInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int next = delegate.read();
            if (next != -1) {
                count(1);
            }
            return next;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > maxBytes) {
                throw new IOException("Request body exceeds " + maxBytes + " bytes");
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}

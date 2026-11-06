package com.ledgerly.shared.web;

import com.ledgerly.shared.config.LedgerlyLimits;
import com.ledgerly.shared.error.PayloadTooLarge;
import com.ledgerly.shared.error.ProblemFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Caps the size of JSON request bodies (API4). Tomcat only limits form posts and multipart uploads, so without
 * this a client could send a multi-gigabyte JSON document. A declared {@code Content-Length} over the limit is
 * refused with 413 before anything is read; a body without one (chunked) is counted while it is read.
 * Runs before Spring Security, so even unauthenticated callers cannot make us read a huge body.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JsonBodyLimitFilter extends OncePerRequestFilter {

    private final long limit;
    private final ProblemFactory problems;
    private final JsonMapper json;

    JsonBodyLimitFilter(LedgerlyLimits limits, ProblemFactory problems, JsonMapper json) {
        this.limit = limits.maxJsonBodySize().toBytes();
        this.problems = problems;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String type = request.getContentType();
        return type == null || !isJson(type);
    }

    private static boolean isJson(String contentType) {
        try {
            MediaType media = MediaType.parseMediaType(contentType);
            return media.isCompatibleWith(MediaType.APPLICATION_JSON)
                    || "json".equals(media.getSubtype())
                    || media.getSubtype().endsWith("+json");
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > limit) {
            reject(request, response);
            return;
        }
        chain.doFilter(new CountingRequest(request, limit), response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemDetail problem =
                problems.create(new PayloadTooLarge("The request body is larger than " + limit + " bytes"));
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // The body was not read: tell the client we will not keep the connection for it.
        response.setHeader("Connection", "close");
        response.getWriter().write(json.writeValueAsString(problem));
    }

    private static final class CountingRequest extends HttpServletRequestWrapper {

        private final long limit;

        CountingRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    if (b >= 0) count(1);
                    return b;
                }

                @Override
                public int read(byte[] buffer, int off, int len) throws IOException {
                    int n = delegate.read(buffer, off, len);
                    if (n > 0) count(n);
                    return n;
                }

                private void count(long n) {
                    read += n;
                    if (read > limit) {
                        throw new PayloadTooLarge("The request body is larger than " + limit + " bytes");
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
            };
        }
    }
}

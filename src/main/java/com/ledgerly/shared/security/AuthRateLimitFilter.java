package com.ledgerly.shared.security;

import com.ledgerly.shared.ratelimit.RateLimit;
import com.ledgerly.shared.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits the endpoints that need no token (register, verify, login, refresh) by client address, so they cannot be
 * used to guess passwords or flood the mailbox of a victim. The address is the one the servlet container reports;
 * behind a reverse proxy set {@code server.forward-headers-strategy=native} so that it is the real client and not
 * the proxy.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    public static final String SCOPE = "auth-ip";
    private static final String PREFIX = "/api/v1/auth/";

    private final RateLimiter limiter;
    private final RateLimit limit;
    private final ProblemSecurityHandler problems;

    public AuthRateLimitFilter(RateLimiter limiter, int requestsPerMinute, ProblemSecurityHandler problems) {
        this.limiter = limiter;
        this.limit = RateLimit.perMinute(requestsPerMinute);
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var decision = limiter.tryConsume(SCOPE, request.getRemoteAddr(), limit);
        if (!decision.allowed()) {
            problems.rateLimited(request, response, decision.retryAfter());
            return;
        }
        chain.doFilter(request, response);
    }
}

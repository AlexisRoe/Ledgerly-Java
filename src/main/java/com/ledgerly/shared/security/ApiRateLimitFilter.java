package com.ledgerly.shared.security;

import com.ledgerly.shared.ratelimit.RateLimit;
import com.ledgerly.shared.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A request budget per authenticated user (API4): the same user gets the same bucket on every instance of this
 * process, whatever device or address they use. Runs after the token has been checked, so the key is the
 * verified user id and never something the caller can choose.
 */
public class ApiRateLimitFilter extends OncePerRequestFilter {

    public static final String SCOPE = "api";

    private final RateLimiter limiter;
    private final RateLimit limit;
    private final ProblemSecurityHandler problems;

    public ApiRateLimitFilter(RateLimiter limiter, int requestsPerMinute, ProblemSecurityHandler problems) {
        this.limiter = limiter;
        this.limit = RateLimit.perMinute(requestsPerMinute);
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token
                && token.getToken().getSubject() != null) {
            var decision = limiter.tryConsume(SCOPE, token.getToken().getSubject(), limit);
            if (!decision.allowed()) {
                problems.rateLimited(request, response, decision.retryAfter());
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

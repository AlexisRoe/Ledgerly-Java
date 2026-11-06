package com.ledgerly.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Puts the caller's user id (never the email) on every log line of the request. */
public class MdcUserFilter extends OncePerRequestFilter {

    public static final String KEY = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean set = false;
        if (authentication instanceof JwtAuthenticationToken token
                && token.getToken().getSubject() != null) {
            MDC.put(KEY, token.getToken().getSubject());
            set = true;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (set) {
                MDC.remove(KEY);
            }
        }
    }
}

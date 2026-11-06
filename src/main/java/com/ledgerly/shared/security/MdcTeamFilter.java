package com.ledgerly.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Puts the team id of {@code /api/v1/teams/{teamId}/...} requests on every log line (never a name or email). */
public class MdcTeamFilter extends OncePerRequestFilter {

    public static final String KEY = "teamId";

    private static final Pattern TEAM_PATH = Pattern.compile(
            "^/api/v1/teams/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:[/:].*)?$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Matcher matcher = TEAM_PATH.matcher(request.getRequestURI());
        boolean set = matcher.matches();
        if (set) {
            MDC.put(KEY, matcher.group(1));
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

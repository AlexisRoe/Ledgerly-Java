package com.ledgerly.identity.internal.web;

import com.ledgerly.shared.config.LedgerlyLimits;
import com.ledgerly.shared.ratelimit.RateLimiter;
import com.ledgerly.shared.security.ApiRateLimitFilter;
import com.ledgerly.shared.security.AuthRateLimitFilter;
import com.ledgerly.shared.security.MdcTeamFilter;
import com.ledgerly.shared.security.MdcUserFilter;
import com.ledgerly.shared.security.ProblemSecurityHandler;
import com.ledgerly.shared.security.SecurityProperties;
import java.util.List;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Deny by default: everything needs a valid access token except the few paths listed here. */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    /**
     * Actuator runs on its own port (8081) and exposes only health, info and prometheus, which probes and the metrics
     * scraper need without a token.
     * The port is not published outside the cluster; every other actuator endpoint would stay denied.
     */
    @Bean
    @Order(1)
    SecurityFilterChain actuatorSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> a.requestMatchers(EndpointRequest.to("health", "info", "prometheus"))
                        .permitAll()
                        .anyRequest()
                        .denyAll());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            ProblemSecurityHandler problems,
            Environment environment,
            RateLimiter rateLimiter,
            LedgerlyLimits limits,
            SecurityProperties securityProperties)
            throws Exception {
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        http
                // Stateless bearer-token API: no session, so no CSRF token. The one cookie-based
                // endpoint pair (refresh, logout) is protected as described in ADR 0004.
                .csrf(AbstractHttpConfigurer::disable)
                // Only the origins in ledgerly.security.allowed-origins may call us from a browser (none by default).
                .cors(c -> c.configurationSource(corsSource(securityProperties)))
                .headers(h -> h.contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(f -> f.deny())
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)
                                // Sent on every response: browsers ignore it over plain http, and a TLS-terminating
                                // proxy in front of us makes every request look like http to this application.
                                .requestMatcher(AnyRequestMatcher.INSTANCE))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        // A JSON API has nothing to load, so nothing may be loaded: that also stops a response
                        // from being rendered as a page. Swagger UI (local only) needs a little more.
                        .addHeaderWriter((request, response) -> response.setHeader(
                                "Content-Security-Policy",
                                local && request.getRequestURI().startsWith("/swagger-ui/") ? SWAGGER_CSP : API_CSP)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> {
                    a.requestMatchers("/api/v1/auth/**", "/.well-known/jwks.json")
                            .permitAll();
                    if (local) {
                        a.requestMatchers(HttpMethod.GET, "/swagger-ui/**").permitAll();
                    }
                    a.anyRequest().authenticated();
                })
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .addFilterAfter(new MdcUserFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new MdcTeamFilter(), MdcUserFilter.class)
                // Unauthenticated endpoints are limited per client address, everything else per verified user.
                .addFilterBefore(
                        new AuthRateLimitFilter(rateLimiter, limits.authRequestsPerMinute(), problems),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(
                        new ApiRateLimitFilter(rateLimiter, limits.requestsPerMinute(), problems), MdcTeamFilter.class);
        return http.build();
    }

    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";
    static final String SWAGGER_CSP =
            "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'";

    private static CorsConfigurationSource corsSource(SecurityProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "If-Match", "Idempotency-Key", "X-Requested-With"));
        cors.setExposedHeaders(List.of("ETag", "Location", "Retry-After", "X-Total-Count", "X-Truncated"));
        // Credentials (the refresh cookie) are only ever sent to the origins listed above.
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}

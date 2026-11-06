package com.ledgerly.identity.internal.web;

import com.ledgerly.shared.security.MdcTeamFilter;
import com.ledgerly.shared.security.MdcUserFilter;
import com.ledgerly.shared.security.ProblemSecurityHandler;
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

/** Deny by default: everything needs a valid access token except the few paths listed here. */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    /**
     * Actuator runs on its own port (8081) and exposes only health and info, which probes need without a token.
     * The port is not published outside the cluster; every other actuator endpoint would stay denied.
     */
    @Bean
    @Order(1)
    SecurityFilterChain actuatorSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> a.requestMatchers(EndpointRequest.to("health", "info"))
                        .permitAll()
                        .anyRequest()
                        .denyAll());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurity(HttpSecurity http, ProblemSecurityHandler problems, Environment environment)
            throws Exception {
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        http
                // Stateless bearer-token API: no session, so no CSRF token. The one cookie-based
                // endpoint pair (refresh, logout) is protected as described in ADR 0004.
                .csrf(AbstractHttpConfigurer::disable)
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
                .addFilterAfter(new MdcTeamFilter(), MdcUserFilter.class);
        return http.build();
    }
}

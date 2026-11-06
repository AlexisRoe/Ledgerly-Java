package com.ledgerly.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class MdcUserFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void putsTheUserIdOnTheLogContextOnlyForTheRequest() throws Exception {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "ES256")
                .subject("11111111-1111-1111-1111-111111111111")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        AtomicReference<String> during = new AtomicReference<>();

        new MdcUserFilter()
                .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain() {
                    @Override
                    public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                        during.set(MDC.get("userId"));
                    }
                });

        assertThat(during.get()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    void anonymousRequestsGetNoUserId() throws Exception {
        AtomicReference<String> during = new AtomicReference<>("unset");
        new MdcUserFilter()
                .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain() {
                    @Override
                    public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                        during.set(MDC.get("userId"));
                    }
                });
        assertThat(during.get()).isNull();
    }
}

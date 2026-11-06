package com.ledgerly.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MdcTeamFilterTest {

    private String teamIdSeenFor(String uri) throws Exception {
        AtomicReference<String> during = new AtomicReference<>();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        new MdcTeamFilter().doFilter(request, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                during.set(MDC.get("teamId"));
            }
        });
        assertThat(MDC.get("teamId")).as("cleared after the request").isNull();
        return during.get();
    }

    @Test
    void teamScopedPathsGetTheTeamId() throws Exception {
        String team = "11111111-2222-3333-4444-555555555555";
        assertThat(teamIdSeenFor("/api/v1/teams/" + team + "/expenses")).isEqualTo(team);
        assertThat(teamIdSeenFor("/api/v1/teams/" + team + ":leave")).isEqualTo(team);
        assertThat(teamIdSeenFor("/api/v1/teams/" + team)).isEqualTo(team);
    }

    @Test
    void otherPathsAndNonUuidsGetNone() throws Exception {
        assertThat(teamIdSeenFor("/api/v1/me")).isNull();
        assertThat(teamIdSeenFor("/api/v1/teams")).isNull();
        assertThat(teamIdSeenFor("/api/v1/teams/not-a-uuid/expenses")).isNull();
        assertThat(teamIdSeenFor("/api/v1/teams/{x}%0Afake=1/expenses")).isNull();
    }
}

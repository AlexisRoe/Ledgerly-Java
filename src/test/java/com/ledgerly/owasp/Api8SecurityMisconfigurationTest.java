package com.ledgerly.owasp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerly.shared.security.SecurityProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API8: Security misconfiguration. Controls: security headers, a CORS allowlist, Actuator on its own port with only
 * three endpoints, Swagger UI off outside local development, no stack traces in errors. Proof: Swagger UI is a 404
 * outside the {@code local} profile (and the other settings are checked too).
 */
@Import(Api8SecurityMisconfigurationTest.Boom.class)
class Api8SecurityMisconfigurationTest extends OwaspTestBase {

    /** A test-only endpoint that fails in the worst way: with an exception that carries internal detail. */
    @RestController
    @RequestMapping("/api/v1/test-only")
    static class Boom {
        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("database password is hunter2 and the table is called secret_stuff");
        }
    }

    // --- headers ----------------------------------------------------------------------------------------------------

    @Test
    void everyResponseCarriesTheSecurityHeaders() {
        Team team = newTeam();
        for (Resp r : List.of(
                get("/api/v1/me", team.member()),
                get("/api/v1/me", null),
                get("/api/v1/nothing-here", team.member()))) {
            HttpHeaders h = r.headers();
            assertThat(h.getFirst("Strict-Transport-Security"))
                    .contains("max-age=31536000")
                    .contains("includeSubDomains");
            assertThat(h.getFirst("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
            assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(h.getFirst("X-Frame-Options")).isEqualTo("DENY");
            assertThat(h.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(h.getFirst("Cache-Control")).contains("no-store");
        }
    }

    // --- CORS --------------------------------------------------------------------------------------------------------

    @Test
    void anAllowlistedOriginMayCallFromABrowser() {
        Resp preflight = send(
                HttpMethod.OPTIONS,
                "/api/v1/me",
                null,
                null,
                "Origin",
                "https://app.example.com",
                "Access-Control-Request-Method",
                "GET",
                "Access-Control-Request-Headers",
                "authorization");
        assertThat(preflight.status()).isEqualTo(200);
        assertThat(preflight.headers().getFirst("Access-Control-Allow-Origin")).isEqualTo("https://app.example.com");
        assertThat(preflight.headers().getFirst("Access-Control-Allow-Credentials"))
                .isEqualTo("true");
        assertThat(preflight.headers().getFirst("Access-Control-Allow-Headers")).containsIgnoringCase("authorization");

        Resp actual = send(HttpMethod.GET, "/api/v1/me", newTeam().member(), null, "Origin", "https://app.example.com");
        assertThat(actual.status()).isEqualTo(200);
        assertThat(actual.headers().getFirst("Access-Control-Allow-Origin")).isEqualTo("https://app.example.com");
        assertThat(actual.headers().getFirst("Access-Control-Expose-Headers"))
                .contains("ETag")
                .contains("Retry-After");
    }

    @Test
    void anyOtherOriginIsRefusedAndNeverGetsAWildcard() {
        for (String origin : List.of(
                "https://evil.example.com", "https://app.example.com.evil.test", "http://app.example.com", "null")) {
            Resp preflight = send(
                    HttpMethod.OPTIONS,
                    "/api/v1/me",
                    null,
                    null,
                    "Origin",
                    origin,
                    "Access-Control-Request-Method",
                    "GET");
            assertThat(preflight.status()).as(origin).isEqualTo(403);
            assertThat(preflight.headers().getFirst("Access-Control-Allow-Origin"))
                    .as(origin)
                    .isNull();

            Resp actual = send(HttpMethod.GET, "/api/v1/me", newTeam().member(), null, "Origin", origin);
            assertThat(actual.status()).as(origin).isEqualTo(403);
            assertThat(actual.headers().getFirst("Access-Control-Allow-Origin"))
                    .as(origin)
                    .isNull();
        }
    }

    @Test
    void theAllowlistRefusesWildcardsPathsAndSloppyEntries() {
        for (String bad : List.of(
                "*",
                "https://*.example.com",
                "https://app.example.com/",
                "https://app.example.com/path",
                "app.example.com",
                "ftp://x.test",
                "")) {
            assertThatThrownBy(() -> new SecurityProperties(List.of(bad)))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new SecurityProperties(List.of("https://app.example.com", "http://localhost:3000")).allowedOrigins())
                .hasSize(2);
        assertThat(new SecurityProperties(List.of()).allowedOrigins()).isEmpty();
    }

    // --- actuator
    // ------------------------------------------------------------------------------------------------------

    String management(String path) {
        return "http://localhost:" + managementPort + path;
    }

    int managementStatus(String path) {
        try {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(management(path)))
                    .build();
            return java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void actuatorLivesOnItsOwnPortAndExposesOnlyHealthInfoAndPrometheus() throws Exception {
        assertThat(managementStatus("/actuator/health")).isEqualTo(200);
        assertThat(managementStatus("/actuator/health/liveness")).isEqualTo(200);
        assertThat(managementStatus("/actuator/prometheus")).isEqualTo(200);
        for (String hidden : List.of(
                "/actuator/env",
                "/actuator/beans",
                "/actuator/heapdump",
                "/actuator/loggers",
                "/actuator/mappings",
                "/actuator/configprops")) {
            assertThat(managementStatus(hidden)).as(hidden).isNotEqualTo(200);
        }
    }

    @Test
    void actuatorIsNotReachableOnThePublicPort() {
        for (String path : List.of("/actuator/health", "/actuator/prometheus", "/actuator/env")) {
            assertThat(get(path, null).status()).as(path).isEqualTo(401);
            assertThat(get(path, newTeam().owner()).status()).as(path).isEqualTo(404);
        }
    }

    // --- swagger
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void swaggerUiIsNotServedOutsideLocalDevelopment() {
        Team team = newTeam();
        for (String path :
                List.of("/swagger-ui/index.html", "/swagger-ui/openapi.yaml", "/swagger-ui/swagger-ui-bundle.js")) {
            assertThat(get(path, null).status()).as(path).isEqualTo(401);
            assertThat(get(path, team.owner()).status()).as(path).isEqualTo(404);
        }
    }

    // --- errors
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void anUnexpectedFailureRevealsNothingButATraceId() {
        Resp r = get("/api/v1/test-only/boom", newTeam().member());
        assertThat(r.status()).isEqualTo(500);
        assertThat(r.headers().getContentType().toString()).contains("application/problem+json");
        assertThat(r.json().get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(r.text())
                .doesNotContain("hunter2")
                .doesNotContain("secret_stuff")
                .doesNotContain("IllegalStateException")
                .doesNotContain("at com.ledgerly")
                .doesNotContainIgnoringCase("stacktrace")
                .doesNotContainIgnoringCase("exception");
        assertThat(r.json().propertyNames())
                .isSubsetOf("type", "title", "status", "code", "traceId", "instance", "detail");
    }

    @Test
    void notFoundAndBadMethodsAreProblemsNotWhitelabelPages() {
        Team team = newTeam();
        for (Resp r : List.of(
                get("/api/v1/does-not-exist", team.member()),
                send(HttpMethod.PUT, "/api/v1/me", team.member(), "{}"))) {
            assertThat(r.headers().getContentType().toString()).contains("problem+json");
            assertThat(r.text())
                    .doesNotContainIgnoringCase("whitelabel")
                    .doesNotContain("Tomcat")
                    .doesNotContain("org.springframework");
        }
    }
}

package com.ledgerly.owasp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * API9: Improper inventory management. Controls: the OpenAPI file is the inventory, paths are versioned, CI fails on
 * undocumented endpoints. Proof: a contract test compares the endpoints the application serves with the file, and
 * the file it serves with the one in the repository.
 */
@ActiveProfiles({"local", "test"}) // local, because that is where the spec is served
class Api9ImproperInventoryManagementTest extends OwaspTestBase {

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    /** "METHOD /path" for everything the application maps, except the error page. */
    Set<String> mappedEndpoints() {
        Set<String> mapped = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (pattern.equals("/error") || pattern.contains("/test-only/")) continue;
                info.getMethodsCondition().getMethods().forEach(m -> mapped.add(m.name() + " " + pattern));
            }
        });
        return mapped;
    }

    @SuppressWarnings("unchecked")
    static Set<String> documentedEndpoints() throws Exception {
        Set<String> documented = new TreeSet<>();
        try (InputStream in = Api9ImproperInventoryManagementTest.class.getResourceAsStream("/api/openapi.yaml")) {
            Map<String, Object> spec = new Yaml().load(in);
            Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) spec.get("paths");
            paths.forEach((path, ops) -> {
                // A path item may override the document's base URL (the JWKS endpoint lives at the root).
                String base = ops.containsKey("servers") ? "" : "/api/v1";
                ops.keySet().stream()
                        .filter(k ->
                                Set.of("get", "post", "put", "patch", "delete").contains(k))
                        .forEach(k -> documented.add(k.toUpperCase() + " " + base + path));
            });
        }
        return documented;
    }

    /** The comparison itself, separate so that it can be tested with made-up sets. */
    static Map<String, Set<String>> drift(Set<String> mapped, Set<String> documented) {
        Set<String> undocumented = new TreeSet<>(mapped);
        undocumented.removeAll(documented);
        Set<String> unmapped = new TreeSet<>(documented);
        unmapped.removeAll(mapped);
        return Map.of("served but not documented", undocumented, "documented but not served", unmapped);
    }

    @Test
    void everyServedEndpointIsInTheSpecAndEveryDocumentedOneIsServed() throws Exception {
        var drift = drift(mappedEndpoints(), documentedEndpoints());
        assertThat(drift.get("served but not documented"))
                .as("served but not documented")
                .isEmpty();
        assertThat(drift.get("documented but not served"))
                .as("documented but not served")
                .isEmpty();
        assertThat(documentedEndpoints()).hasSizeGreaterThan(30);
    }

    @Test
    void theComparisonDetectsDriftInBothDirections() {
        var drift = drift(Set.of("GET /api/v1/a", "GET /api/v1/secret"), Set.of("GET /api/v1/a", "GET /api/v1/ghost"));
        assertThat(drift.get("served but not documented")).containsExactly("GET /api/v1/secret");
        assertThat(drift.get("documented but not served")).containsExactly("GET /api/v1/ghost");
    }

    @Test
    void everyPathIsVersionedExceptTheKeyDiscoveryDocument() {
        assertThat(mappedEndpoints())
                .allMatch(
                        e -> e.contains(" /api/v1/") || e.endsWith(" /.well-known/jwks.json"),
                        "versioned or the JWKS document");
    }

    @Test
    void theSpecTheApplicationServesIsTheFileInTheRepository() throws Exception {
        String served = get("/swagger-ui/openapi.yaml", null).text();
        String file;
        try (InputStream in = getClass().getResourceAsStream("/api/openapi.yaml")) {
            file = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(served).isEqualTo(file);
        assertThat(new HashSet<>(java.util.List.of(served.split("\n")))).contains("openapi: 3.1.0");
    }
}

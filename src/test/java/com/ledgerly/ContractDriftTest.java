package com.ledgerly;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/** API9: the OpenAPI file is the inventory. Every mapped endpoint is in it, and every documented one is mapped. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ContractDriftTest {

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void specAndControllersAgree() throws Exception {
        Set<String> mapped = new HashSet<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            var patterns = info.getPathPatternsCondition().getPatternValues();
            for (String pattern : patterns) {
                if (pattern.equals("/error")) continue;
                for (var m : info.getMethodsCondition().getMethods()) {
                    mapped.add(m.name() + " " + pattern);
                }
            }
        });

        Set<String> documented = new HashSet<>();
        try (InputStream in = getClass().getResourceAsStream("/api/openapi.yaml")) {
            Map<String, Object> spec = new Yaml().load(in);
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) spec.get("paths");
            paths.forEach((path, ops) -> ops.keySet().stream()
                    .filter(k -> Set.of("get", "post", "put", "patch", "delete").contains(k))
                    .forEach(k -> documented.add(k.toUpperCase() + " /api/v1" + path)));
        }

        assertThat(mapped).as("mapped but undocumented").isSubsetOf(documented);
        assertThat(documented).as("documented but not mapped").isSubsetOf(mapped);
        assertThat(documented).isNotEmpty();
    }
}

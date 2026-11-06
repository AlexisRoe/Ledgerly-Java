package com.ledgerly;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.context.WebApplicationContext;

class SwaggerUiProfileTest {

    @Nested
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ActiveProfiles({"local", "test"})
    class Local {
        @Autowired
        WebApplicationContext context;

        @Test
        void servesUiAndSpec() {
            var client = RestTestClient.bindToApplicationContext(context).build();
            client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
            client.get()
                    .uri("/swagger-ui/openapi.yaml")
                    .exchange()
                    .expectStatus()
                    .isOk();
            client.get()
                    .uri("/swagger-ui/swagger-ui-bundle.js")
                    .exchange()
                    .expectStatus()
                    .isOk();
        }
    }

    @Nested
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    class NotLocal {
        @Autowired
        WebApplicationContext context;

        @Test
        void isNotServed() {
            var client = RestTestClient.bindToApplicationContext(context).build();
            client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isNotFound();
        }
    }
}

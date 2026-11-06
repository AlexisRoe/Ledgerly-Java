package com.ledgerly;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

class SwaggerUiProfileTest {

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
    @Import(TestcontainersConfiguration.class)
    @ActiveProfiles({"local", "test"})
    class Local {
        @LocalServerPort
        int port;

        @Test
        void servesUiAndSpec() {
            var client = RestTestClient.bindToServer()
                    .baseUrl("http://localhost:" + port)
                    .build();
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
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
    @Import(TestcontainersConfiguration.class)
    @ActiveProfiles({"local", "test"})
    class LocalDocsShortcut {
        @LocalServerPort
        int port;

        @Test
        void docsLeadsToTheUiWithoutAToken() {
            var client = RestTestClient.bindToServer()
                    .baseUrl("http://localhost:" + port)
                    .build();
            client.get()
                    .uri("/docs")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String.class)
                    .value(page -> org.assertj.core.api.Assertions.assertThat(page)
                            .as("followed the redirect to the UI page")
                            .containsIgnoringCase("swagger"));
        }

        @Test
        void onlyReadingIsOpen() {
            var client = RestTestClient.bindToServer()
                    .baseUrl("http://localhost:" + port)
                    .build();
            client.post().uri("/docs").exchange().expectStatus().isUnauthorized();
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
    @Import(TestcontainersConfiguration.class)
    class NotLocal {
        @LocalServerPort
        int port;

        @Test
        void isDeniedByDefault() {
            var client = RestTestClient.bindToServer()
                    .baseUrl("http://localhost:" + port)
                    .build();
            client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isUnauthorized();
        }

        @Test
        void docsIsDeniedToo() {
            var client = RestTestClient.bindToServer()
                    .baseUrl("http://localhost:" + port)
                    .build();
            client.get().uri("/docs").exchange().expectStatus().isUnauthorized();
        }
    }
}

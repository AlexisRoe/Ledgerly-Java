package com.ledgerly.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.TestcontainersConfiguration;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** A client can hide the size of a body by sending it in chunks; the limit must hold anyway, and only for big bodies. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class ChunkedBodyLimitTest {

    @LocalServerPort
    int port;

    HttpResponse<String> postChunked(String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        // ofInputStream has no known length, so the request goes out with Transfer-Encoding: chunked
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aChunkedBodyOverTheLimitIsRefusedWith413() throws Exception {
        String huge = "{\"email\":\"a@example.com\",\"password\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}";

        var response = postChunked(huge);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void aSmallChunkedBodyIsNotTouchedByTheLimit() throws Exception {
        var response = postChunked("{\"email\":\"nobody@example.com\",\"password\":\"correct-horse-battery\"}");

        assertThat(response.statusCode())
                .as("reaches the login, which refuses an unknown user")
                .isEqualTo(401);
    }
}

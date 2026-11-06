package com.ledgerly.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The real S3 adapter against a real S3-compatible store (SeaweedFS), the same one compose.yaml starts. */
@Testcontainers
class S3ObjectStorageTest {

    static GenericContainer<?> seaweed;
    static S3ObjectStorage storage;
    static StorageProperties props;

    @BeforeAll
    static void start() {
        seaweed = new GenericContainer<>("chrislusf/seaweedfs:latest")
                .withCommand("server", "-s3", "-s3.port=8333", "-dir=/data")
                .withExposedPorts(8333)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
        seaweed.start();
        props = new StorageProperties(new StorageProperties.S3(
                "http://" + seaweed.getHost() + ":" + seaweed.getMappedPort(8333),
                "us-east-1",
                "test-receipts",
                "test",
                "test-secret"));
        storage = new S3ObjectStorage(props);
        // The store needs a moment after it starts listening before it accepts bucket calls.
        await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(() -> {
            storage.ensureBucket();
            storage.put("probe", tempFile("x"), "application/pdf");
            return true;
        });
    }

    @AfterAll
    static void stop() {
        if (storage != null) storage.close();
        if (seaweed != null) seaweed.stop();
    }

    static Path tempFile(String content) throws Exception {
        Path file = Files.createTempFile("s3test-", ".bin");
        Files.writeString(file, content);
        file.toFile().deleteOnExit();
        return file;
    }

    @Test
    void putThenOpenReturnsTheSameBytes() throws Exception {
        storage.put("receipts/a", tempFile("hello receipt"), "application/pdf");
        try (var in = storage.open("receipts/a")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello receipt");
        }
    }

    @Test
    void moveRelocatesTheObject() throws Exception {
        storage.put("receipts/b", tempFile("infected"), "application/pdf");
        storage.move("receipts/b", "quarantine/b");
        try (var in = storage.open("quarantine/b")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("infected");
        }
        assertThatThrownBy(() -> storage.open("receipts/b").close()).isInstanceOf(Exception.class);
    }

    @Test
    void deleteRemovesTheObject() throws Exception {
        storage.put("receipts/c", tempFile("bye"), "application/pdf");
        storage.delete("receipts/c");
        assertThatThrownBy(() -> storage.open("receipts/c").close()).isInstanceOf(Exception.class);
    }

    @Test
    void aPresignedLinkDownloadsAsAnAttachmentUnderTheGivenName() throws Exception {
        storage.put("receipts/d", tempFile("%PDF-1.4 content"), "application/pdf");
        var url = storage.presignGet("receipts/d", "lunch.pdf", "application/pdf", Duration.ofMinutes(5));
        assertThat(url.toString()).contains("X-Amz-Expires=300").contains("X-Amz-Signature=");

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("%PDF-1.4 content");
        assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
                .contains("attachment")
                .contains("lunch.pdf");
    }

    // --- observability: every call to the object store is a timer and a span ----------------------------------------

    @Test
    void everyOperationIsAnObservationTaggedWithTheOperationButNeverTheKey() throws Exception {
        var observedOps = new java.util.LinkedHashMap<String, java.util.function.Consumer<S3ObjectStorage>>();
        observedOps.put("put", s -> {
            try {
                s.put("receipts/observed", tempFile("x"), "application/pdf");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        observedOps.put("open", s -> {
            try (var in = s.open("receipts/observed")) {
                in.readAllBytes();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        observedOps.put(
                "presign", s -> s.presignGet("receipts/observed", "r.pdf", "application/pdf", Duration.ofMinutes(5)));
        observedOps.put("move", s -> s.move("receipts/observed", "quarantine/observed"));
        observedOps.put("delete", s -> s.delete("quarantine/observed"));

        for (var entry : observedOps.entrySet()) {
            var registry = io.micrometer.observation.tck.TestObservationRegistry.create();
            var observed = new S3ObjectStorage(props, registry);
            try {
                entry.getValue().accept(observed);
            } finally {
                observed.close();
            }
            io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat(registry)
                    .hasSingleObservationThat()
                    .hasNameEqualTo("ledgerly.s3")
                    .hasLowCardinalityKeyValue("operation", entry.getKey())
                    .doesNotHaveLowCardinalityKeyValueWithKey("key")
                    .doesNotHaveHighCardinalityKeyValueWithKey("key")
                    .hasBeenStopped();
        }
    }
}

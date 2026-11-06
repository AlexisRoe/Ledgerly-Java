package com.ledgerly.receipts.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerly.receipts.internal.application.MalwareScanner;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Checks our INSTREAM framing against a tiny stand-in for clamd: command, length-prefixed chunks, zero-length
 * terminator, NUL-terminated reply.
 */
class ClamAvScannerTest {

    record Received(String command, byte[] payload) {}

    /** Accepts one connection, reads a full INSTREAM request, answers with {@code reply}. */
    private static CompletableFuture<Received> fakeClamd(ServerSocket server, String reply) {
        return CompletableFuture.supplyAsync(() -> {
            try (var socket = server.accept()) {
                var in = new DataInputStream(socket.getInputStream());
                var command = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) > 0) command.write(b);
                var payload = new ByteArrayOutputStream();
                while (true) {
                    int length = in.readInt();
                    if (length == 0) break;
                    payload.write(in.readNBytes(length));
                }
                socket.getOutputStream().write((reply + "\0").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                return new Received(command.toString(StandardCharsets.US_ASCII), payload.toByteArray());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static ClamAvScanner scannerFor(int port) {
        var props = new ReceiptsProperties(
                new ReceiptsProperties.ClamAv("localhost", port, Duration.ofSeconds(2), Duration.ofSeconds(5)));
        return new ClamAvScanner(props);
    }

    @Test
    void sendsTheFileInChunksAndReadsAnOkReply() throws Exception {
        byte[] content = new byte[20_000]; // more than one 8 KB chunk
        java.util.Arrays.fill(content, (byte) 7);
        try (var server = new ServerSocket(0)) {
            var received = fakeClamd(server, "stream: OK");
            var result = scannerFor(server.getLocalPort()).scan(new ByteArrayInputStream(content));
            assertThat(result).isInstanceOf(MalwareScanner.Result.Clean.class);
            assertThat(received.get().command()).isEqualTo("zINSTREAM");
            assertThat(received.get().payload()).isEqualTo(content);
        }
    }

    @Test
    void parsesAFoundReplyIntoTheSignatureName() throws Exception {
        try (var server = new ServerSocket(0)) {
            fakeClamd(server, "stream: Eicar-Test-Signature FOUND");
            var result = scannerFor(server.getLocalPort()).scan(new ByteArrayInputStream(new byte[] {1, 2, 3}));
            assertThat(result).isEqualTo(new MalwareScanner.Result.Infected("Eicar-Test-Signature"));
        }
    }

    @Test
    void anErrorReplyIsAnIOExceptionSoItCanBeRetried() throws Exception {
        try (var server = new ServerSocket(0)) {
            fakeClamd(server, "INSTREAM size limit exceeded. ERROR");
            assertThatThrownBy(() -> scannerFor(server.getLocalPort()).scan(new ByteArrayInputStream(new byte[] {1})))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Unexpected ClamAV reply");
        }
    }

    @Test
    void anUnreachableScannerIsAnIOException() throws Exception {
        int closedPort;
        try (var s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        assertThatThrownBy(() -> scannerFor(closedPort).scan(new ByteArrayInputStream(new byte[] {1})))
                .isInstanceOf(IOException.class);
    }
}

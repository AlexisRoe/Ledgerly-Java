package com.ledgerly.receipts.internal.infrastructure;

import com.ledgerly.receipts.internal.application.MalwareScanner;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * A small ClamAV client speaking clamd's {@code INSTREAM} protocol over a socket: send {@code zINSTREAM\0}, then
 * the file as chunks of (4-byte big-endian length + bytes), then a zero-length chunk; clamd answers
 * {@code stream: OK} or {@code stream: <signature> FOUND}, terminated by a NUL byte.
 */
@Component
@Profile("!test")
class ClamAvScanner implements MalwareScanner {

    private static final int CHUNK = 8192;

    private final ReceiptsProperties.ClamAv config;
    private final ObservationRegistry observations;

    /** @param props clamd host, port and timeouts */
    ClamAvScanner(ReceiptsProperties props) {
        this(props, ObservationRegistry.NOOP);
    }

    @Autowired
    ClamAvScanner(ReceiptsProperties props, ObservationRegistry observations) {
        this.config = props.clamav();
        this.observations = observations;
    }

    /** Retried because clamd may be restarting or reloading its signatures; IOExceptions only. */
    @Override
    @Retryable(includes = IOException.class, maxRetries = 3, delay = 500, multiplier = 2)
    public Result scan(InputStream content) throws IOException {
        // A timer and a span for every attempt (so retries show up), tagged with the outcome, never the content.
        Observation observation = Observation.createNotStarted("ledgerly.clamav.scan", observations)
                .contextualName("clamav scan")
                .start();
        try (Observation.Scope scope = observation.openScope()) {
            Result result = scanOnce(content);
            observation.lowCardinalityKeyValue("outcome", result instanceof Result.Clean ? "clean" : "infected");
            return result;
        } catch (IOException | RuntimeException e) {
            observation.lowCardinalityKeyValue("outcome", "error");
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    private Result scanOnce(InputStream content) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(config.host(), config.port()), (int)
                    config.connectTimeout().toMillis());
            socket.setSoTimeout((int) config.readTimeout().toMillis());
            OutputStream out = socket.getOutputStream();
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[CHUNK];
            int read;
            while ((read = content.read(buffer)) != -1) {
                out.write(ByteBuffer.allocate(4).putInt(read).array());
                out.write(buffer, 0, read);
            }
            out.write(new byte[] {0, 0, 0, 0});
            out.flush();
            return parse(readReply(socket.getInputStream()));
        }
    }

    /** Reads clamd's NUL-terminated answer (bounded) as text. */
    static String readReply(InputStream in) throws IOException {
        var reply = new java.io.ByteArrayOutputStream();
        int b;
        while ((b = in.read()) > 0) {
            reply.write(b);
        }
        return reply.toString(StandardCharsets.US_ASCII).strip();
    }

    private static final java.util.regex.Pattern OK = java.util.regex.Pattern.compile("^stream: OK$");
    private static final java.util.regex.Pattern FOUND =
            java.util.regex.Pattern.compile("^stream: ([A-Za-z0-9._+-]{1,200}) FOUND$");

    /**
     * The reply is data from another system, so it is matched against the exact shapes clamd uses and nothing
     * else. Anything unexpected (an error message, a reply that mentions both OK and FOUND, garbage) is an error,
     * never "clean": a file is only accepted when the scanner clearly said so.
     */
    static Result parse(String reply) throws IOException {
        if (OK.matcher(reply).matches()) {
            return new Result.Clean();
        }
        var found = FOUND.matcher(reply);
        if (found.matches()) {
            return new Result.Infected(found.group(1));
        }
        throw new IOException("Unexpected ClamAV reply");
    }
}

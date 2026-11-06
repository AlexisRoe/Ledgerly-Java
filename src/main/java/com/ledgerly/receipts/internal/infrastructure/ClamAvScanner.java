package com.ledgerly.receipts.internal.infrastructure;

import com.ledgerly.receipts.internal.application.MalwareScanner;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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

    ClamAvScanner(ReceiptsProperties props) {
        this.config = props.clamav();
    }

    /** Retried because clamd may be restarting or reloading its signatures; IOExceptions only. */
    @Override
    @Retryable(includes = IOException.class, maxRetries = 3, delay = 500, multiplier = 2)
    public Result scan(InputStream content) throws IOException {
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

    static String readReply(InputStream in) throws IOException {
        var reply = new java.io.ByteArrayOutputStream();
        int b;
        while ((b = in.read()) > 0) {
            reply.write(b);
        }
        return reply.toString(StandardCharsets.US_ASCII).strip();
    }

    static Result parse(String reply) throws IOException {
        if (reply.endsWith(" OK")) {
            return new Result.Clean();
        }
        if (reply.endsWith(" FOUND")) {
            String signature = reply.replaceFirst("^[^:]*:\\s*", "").replaceFirst("\\s+FOUND$", "");
            return new Result.Infected(signature);
        }
        throw new IOException("Unexpected ClamAV reply: " + reply);
    }
}

package com.ledgerly.shared.io;

import com.ledgerly.shared.error.PayloadTooLarge;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Fails with 413 as soon as more than {@code limit} bytes have been read, however large the client claims to send. */
public final class CappedInputStream extends FilterInputStream {

    private final long limit;
    private long read;

    /**
     * @param limit most bytes that may be read; reading beyond it throws {@link com.ledgerly.shared.error.PayloadTooLarge}
     */
    public CappedInputStream(InputStream in, long limit) {
        super(in);
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            count(1);
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
        int n = super.read(buffer, off, len);
        if (n > 0) {
            count(n);
        }
        return n;
    }

    private void count(long n) {
        read += n;
        if (read > limit) {
            throw new PayloadTooLarge("The file is larger than " + (limit / (1024 * 1024)) + " MB");
        }
    }
}

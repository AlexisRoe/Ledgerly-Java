package com.ledgerly.shared.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 (RFC 9562): a 48-bit millisecond timestamp followed by random bits, so ids sort roughly by creation
 * time and index inserts stay append-friendly.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    /** A UUIDv7: the first 48 bits are the current time, so new ids sort after older ones (index-friendly). */
    public static UUID generate() {
        return generate(System.currentTimeMillis());
    }

    /** Same for a given instant in epoch milliseconds (tests). */
    static UUID generate(long epochMillis) {
        long msb = (epochMillis << 16) | 0x7000L | (RANDOM.nextLong() & 0x0FFFL);
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}

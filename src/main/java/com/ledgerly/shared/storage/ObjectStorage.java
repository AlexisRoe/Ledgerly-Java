package com.ledgerly.shared.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Port: where uploaded files live (receipts under {@code receipts/}, imports under {@code imports/}). The adapter
 * talks to an S3-compatible store; tests use a directory.
 */
public interface ObjectStorage {

    void put(String key, Path file, String contentType) throws IOException;

    InputStream open(String key) throws IOException;

    /** Removes the object; a missing key is not an error. */
    void delete(String key);

    /** Copies then deletes: used to quarantine an infected file. */
    void move(String fromKey, String toKey);

    /**
     * A time-limited link that downloads the object as an attachment under {@code downloadName}. The store is
     * private; this link is the only way in.
     */
    URI presignGet(String key, String downloadName, String contentType, Duration ttl);
}

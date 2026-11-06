package com.ledgerly.imports.internal.infrastructure;

import com.ledgerly.shared.storage.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.AbstractResource;

/**
 * Lets a Spring Batch reader read a CSV that lives in the object store. Each call opens the object afresh, which is
 * what makes a restarted import possible: the reader reopens the file and skips what it already processed.
 */
final class StorageResource extends AbstractResource {

    private final ObjectStorage storage;
    private final String key;

    /** @param key the object-store key of the uploaded CSV */
    StorageResource(ObjectStorage storage, String key) {
        this.storage = storage;
        this.key = key;
    }

    @Override
    public String getDescription() {
        return "object store key [" + key + "]";
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return storage.open(key);
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public boolean isReadable() {
        return true;
    }
}

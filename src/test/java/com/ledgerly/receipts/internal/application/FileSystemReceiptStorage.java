package com.ledgerly.receipts.internal.application;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Test double for the object store: a temp directory, plus a record of the presigned links it handed out. */
@Component
@Profile("test")
public class FileSystemReceiptStorage implements ReceiptStorage {

    public record Presigned(String key, String downloadName, String contentType, Duration ttl, URI url) {}

    private final Path root;
    public final List<Presigned> presigned = new CopyOnWriteArrayList<>();

    public FileSystemReceiptStorage() throws IOException {
        this.root = Files.createTempDirectory("receipt-store-");
    }

    private Path path(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Key escapes the store: " + key);
        }
        return resolved;
    }

    @Override
    public void put(String key, Path file, String contentType) throws IOException {
        Path target = path(key);
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(path(key));
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(path(key));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void move(String fromKey, String toKey) {
        try {
            Path target = path(toKey);
            Files.createDirectories(target.getParent());
            Files.move(path(fromKey), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (NoSuchFileException e) {
            throw new IllegalStateException("No such object: " + fromKey, e);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public URI presignGet(String key, String downloadName, String contentType, Duration ttl) {
        URI url = URI.create("https://storage.test/" + key + "?filename="
                + URLEncoder.encode(downloadName, StandardCharsets.UTF_8) + "&ttl=" + ttl.toSeconds());
        presigned.add(new Presigned(key, downloadName, contentType, ttl, url));
        return url;
    }

    public boolean exists(String key) {
        return Files.exists(path(key));
    }

    public long objectCount() throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    public byte[] read(String key) throws IOException {
        return Files.readAllBytes(path(key));
    }
}

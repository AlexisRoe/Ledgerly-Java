package com.ledgerly.shared.storage;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** The object store adapter: any S3-compatible service (SeaweedFS locally). The bucket is private. */
@Component
@Profile("!test")
public class S3ObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final String bucket;
    private final S3Client s3;
    private final S3Presigner presigner;
    private final ObservationRegistry observations;

    /** Without metrics. */
    public S3ObjectStorage(StorageProperties props) {
        this(props, ObservationRegistry.NOOP);
    }

    /** Every call is timed and traced as {@code ledgerly.s3} tagged with the operation, never the key. */
    @Autowired
    public S3ObjectStorage(StorageProperties props, ObservationRegistry observations) {
        this.observations = observations;
        var s3Props = props.s3();
        this.bucket = s3Props.bucket();
        var credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create(s3Props.accessKey(), s3Props.secretKey()));
        URI endpoint = URI.create(s3Props.endpoint());
        Region region = Region.of(s3Props.region());
        // Path-style addressing (host/bucket/key): S3-compatible stores rarely support bucket sub-domains.
        this.s3 = S3Client.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentials)
                .httpClient(UrlConnectionHttpClient.create())
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    /**
     * Creates the bucket if it is missing. Runs once the application is up, not while it starts: an object store that
     * is slow or down must not delay start-up (the AWS client retries for seconds). {@link #put} also creates the
     * bucket on demand, so an upload never depends on this having run.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (RuntimeException e) {
            // Do not stop the application: uploads will fail with a clear error until the store is reachable.
            log.warn(
                    "Object store not reachable at start-up ({}); receipts cannot be stored yet",
                    e.getClass().getSimpleName());
        }
    }

    /** Releases the S3 clients at shutdown. */
    @PreDestroy
    public void close() {
        s3.close();
        presigner.close();
    }

    @Override
    public void put(String key, Path file, String contentType) throws IOException {
        try {
            putObject(key, file, contentType);
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            putObject(key, file, contentType);
        }
    }

    private void putObject(String key, Path file, String contentType) {
        observe(
                "put",
                () -> s3.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .contentType(contentType)
                                .build(),
                        RequestBody.fromFile(file)));
    }

    @Override
    public InputStream open(String key) throws IOException {
        return observe(
                "open",
                () -> s3.getObject(
                        GetObjectRequest.builder().bucket(bucket).key(key).build()));
    }

    @Override
    public void delete(String key) {
        observe(
                "delete",
                () -> s3.deleteObject(
                        DeleteObjectRequest.builder().bucket(bucket).key(key).build()));
    }

    @Override
    public void move(String fromKey, String toKey) {
        observe("move", () -> {
            s3.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket)
                    .sourceKey(fromKey)
                    .destinationBucket(bucket)
                    .destinationKey(toKey)
                    .build());
            s3.deleteObject(
                    DeleteObjectRequest.builder().bucket(bucket).key(fromKey).build());
        });
    }

    @Override
    public URI presignGet(String key, String downloadName, String contentType, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .responseContentDisposition("attachment; filename=\"" + downloadName + "\"")
                .responseContentType(contentType)
                .build();
        var presigned = observe(
                "presign",
                () -> presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(get)
                        .build()));
        try {
            return presigned.url().toURI();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Every call to the object store is an observation: Micrometer records a timer ({@code ledgerly.s3}, tagged with
     * the operation) and, with tracing on, a span in the trace of the request that caused it. Keys and file names are
     * deliberately not tags: they are high-cardinality and may be personal.
     */
    private <T> T observe(String operation, java.util.function.Supplier<T> call) {
        return Observation.createNotStarted("ledgerly.s3", observations)
                .contextualName("s3 " + operation)
                .lowCardinalityKeyValue("operation", operation)
                .observe(call);
    }

    private void observe(String operation, Runnable call) {
        observe(operation, () -> {
            call.run();
            return null;
        });
    }
}

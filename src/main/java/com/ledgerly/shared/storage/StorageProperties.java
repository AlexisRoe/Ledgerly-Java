package com.ledgerly.shared.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** The object store shared by receipts and imports. Defaults match {@code compose.yaml}. */
@Validated
@ConfigurationProperties("ledgerly.storage")
public record StorageProperties(@DefaultValue S3 s3) {

    public record S3(
            @DefaultValue("http://localhost:8333") String endpoint,
            @DefaultValue("us-east-1") String region,
            @DefaultValue("ledgerly-files") String bucket,
            @DefaultValue("ledgerly") String accessKey,
            @DefaultValue("ledgerly-secret") String secretKey) {

        @Override
        public String toString() {
            return "S3[endpoint=" + endpoint + ", bucket=" + bucket + ", credentials=***]";
        }
    }
}

package com.ledgerly.shared.storage;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The object store shared by receipts and imports. The values for local development are in
 * {@code application-local.yaml} (they match {@code compose.yaml}); in {@code prod} they must all be set.
 */
@Validated
@ConfigurationProperties("ledgerly.storage")
public record StorageProperties(@DefaultValue @Valid S3 s3) {

    /** Nothing but the region has a default: an endpoint, bucket or credentials made up here could be wrong silently. */
    public record S3(
            @NotBlank String endpoint,
            @DefaultValue("us-east-1") String region,
            @NotBlank String bucket,
            @NotBlank String accessKey,
            @NotBlank String secretKey) {

        @Override
        public String toString() {
            return "S3[endpoint=" + endpoint + ", bucket=" + bucket + ", credentials=***]";
        }
    }
}

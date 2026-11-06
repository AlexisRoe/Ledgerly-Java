package com.ledgerly.receipts.internal.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Defaults match {@code compose.yaml}. Outside local development every value comes from the environment. */
@Validated
@ConfigurationProperties("ledgerly.receipts")
public record ReceiptsProperties(
        @DefaultValue S3 s3, @DefaultValue ClamAv clamav) {

    public record S3(
            @DefaultValue("http://localhost:8333") String endpoint,
            @DefaultValue("us-east-1") String region,
            @DefaultValue("ledgerly-receipts") String bucket,
            @DefaultValue("ledgerly") String accessKey,
            @DefaultValue("ledgerly-secret") String secretKey) {

        @Override
        public String toString() {
            return "S3[endpoint=" + endpoint + ", bucket=" + bucket + ", credentials=***]";
        }
    }

    public record ClamAv(
            @DefaultValue("localhost") String host,
            @DefaultValue("3310") int port,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("30s") Duration readTimeout) {}
}

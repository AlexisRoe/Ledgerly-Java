package com.ledgerly.receipts.internal.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Defaults match {@code compose.yaml}. */
@Validated
@ConfigurationProperties("ledgerly.receipts")
public record ReceiptsProperties(@DefaultValue ClamAv clamav) {

    public record ClamAv(
            @DefaultValue("localhost") String host,
            @DefaultValue("3310") int port,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("30s") Duration readTimeout) {}
}

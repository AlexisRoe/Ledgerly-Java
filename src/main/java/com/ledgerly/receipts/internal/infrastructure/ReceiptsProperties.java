package com.ledgerly.receipts.internal.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** The host and port have no default: local development sets them in {@code application-local.yaml}. */
@Validated
@ConfigurationProperties("ledgerly.receipts")
public record ReceiptsProperties(@DefaultValue @Valid ClamAv clamav) {

    public record ClamAv(
            @NotBlank String host,
            @Min(1) @Max(65535) int port,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("30s") Duration readTimeout) {}
}

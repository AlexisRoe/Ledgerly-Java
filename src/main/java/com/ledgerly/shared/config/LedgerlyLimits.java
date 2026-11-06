package com.ledgerly.shared.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/** Every limit the API enforces. Later milestones read their caps from here. */
@Validated
@ConfigurationProperties("ledgerly.limits")
public record LedgerlyLimits(
        @DefaultValue("100") @Positive int maxPageSize,
        @DefaultValue("100") @Positive int maxBatchSize,
        @DefaultValue("10MB") DataSize maxUploadSize,
        @DefaultValue("10000") @Positive int maxImportRows,
        @DefaultValue("5") @Positive int maxImportsPerDay,
        @DefaultValue("1MB") DataSize maxJsonBodySize,
        @DefaultValue("10000") @Positive int maxExportRows,
        @DefaultValue("600") @Positive int requestsPerMinute,
        @DefaultValue("60") @Positive int authRequestsPerMinute) {}

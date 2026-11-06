package com.ledgerly.imports.internal.application;

import com.ledgerly.imports.internal.infrastructure.ImportJobs;
import java.util.List;
import java.util.UUID;

/**
 * Progress of an import. {@code status}: QUEUED, RUNNING, COMPLETED or FAILED. {@code errors} are the rejected lines, cut off when {@code errorsTruncated}.
 */
public record ImportView(
        UUID id,
        String status,
        int totalRows,
        int importedRows,
        int skippedRows,
        List<ImportJobs.RowError> errors,
        boolean errorsTruncated) {}

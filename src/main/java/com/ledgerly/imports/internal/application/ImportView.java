package com.ledgerly.imports.internal.application;

import com.ledgerly.imports.internal.infrastructure.ImportJobs;
import java.util.List;
import java.util.UUID;

public record ImportView(
        UUID id,
        String status,
        int totalRows,
        int importedRows,
        int skippedRows,
        List<ImportJobs.RowError> errors,
        boolean errorsTruncated) {}
